#!/bin/bash
set -e

# ==========================================
# Configuration (Set defaults similar to Groovy)
# ==========================================
ENABLE=${ENABLE:-true}

# ถ้า ENABLE=false ให้จบการทำงานทันที
if [[ "$ENABLE" =~ ^(false|False|FALSE|no|NO)$ ]]; then
    echo "[staticSecChecks] disabled"
    exit 0
fi

# Directory & Excludes
SCAN_DIRS=${SCAN_DIRS:-.}
EXCLUDES=${EXCLUDES:-node_modules,dist,build}

# Images
GITLEAKS_IMAGE=${GITLEAKS_IMAGE:-zricethezav/gitleaks:latest}
SEMGREP_IMAGE=${SEMGREP_IMAGE:-semgrep/semgrep:latest}
BANDIT_BASE_IMAGE=${BANDIT_BASE_IMAGE:-python:3.11-slim}

# Gitleaks Config
GITLEAKS_ENABLE=${GITLEAKS_ENABLE:-true}
GITLEAKS_FAIL_ON_FINDING=${GITLEAKS_FAIL_ON_FINDING:-true}
GITLEAKS_HISTORY=${GITLEAKS_HISTORY:-false}

# Semgrep Config
SEMGREP_ENABLE=${SEMGREP_ENABLE:-true}
SEMGREP_CONFIG=${SEMGREP_CONFIG:-p/owasp-top-ten}
SEMGREP_FAIL_ON_FINDING=${SEMGREP_FAIL_ON_FINDING:-false}

# Bandit Config
# Logic: Disable bandit default if project is pure frontend JS
LANG_LOWER=$(echo "${LANGUAGE}" | tr '[:upper:]' '[:lower:]')
PROJ_TYPE_LOWER=$(echo "${PROJECT_TYPE}" | tr '[:upper:]' '[:lower:]')
IS_FRONTEND_JS=false
if [[ "$LANG_LOWER" =~ ^(javascript|typescript)$ ]] && [[ "$PROJ_TYPE_LOWER" =~ ^(frontend|spa|web)$ ]]; then
    IS_FRONTEND_JS=true
fi

DEFAULT_BANDIT_ENABLE=true
if [ "$IS_FRONTEND_JS" = true ]; then DEFAULT_BANDIT_ENABLE=false; fi
BANDIT_ENABLE=${BANDIT_ENABLE:-$DEFAULT_BANDIT_ENABLE}
BANDIT_FAIL_ON_FINDING=${BANDIT_FAIL_ON_FINDING:-false}

# ==========================================
# Preparation
# ==========================================
echo "[DEBUG] PWD=$(pwd)"
mkdir -p reports tools
chmod 777 reports || true

# Helper variables
# Convert comma separated string to space separated for flags
EXCLUDE_FLAGS=$(echo "$EXCLUDES" | tr ',' '\n' | awk '{print "--exclude "$1}' | tr '\n' ' ')
# Scan targets (space separated)
SCAN_TARGETS=$(echo "$SCAN_DIRS" | tr ',' ' ')
# Semgrep Config Flags
SEMGREP_CFG_FLAGS=$(echo "$SEMGREP_CONFIG" | tr ',' '\n' | awk '{print "--config "$1}' | tr '\n' ' ')

GITLEAKS_COUNT=0
SEMGREP_COUNT=0
BANDIT_COUNT=0

# Helper function to count JSON/SARIF results using Python (to avoid dependency on jq)
count_json_results() {
    local file=$1
    local type=$2 # sarif or bandit
    if [ ! -s "$file" ]; then echo 0; return; fi
    
    docker run --rm -v "$PWD:/work:Z" -w /work "$BANDIT_BASE_IMAGE" python -c "
import json, sys
try:
    data = json.load(open('$file'))
    count = 0
    if '$type' == 'sarif':
        for run in data.get('runs', []):
            count += len(run.get('results', []))
    elif '$type' == 'bandit':
        count = len(data.get('results', []))
    print(count)
except Exception:
    print(0)
"
}

# ==========================================
# 1. Gitleaks
# ==========================================
if [[ "$GITLEAKS_ENABLE" =~ ^(true|True|TRUE|yes|YES)$ ]]; then
    HISTORY_FLAG="--no-git"
    if [[ "$GITLEAKS_HISTORY" =~ ^(true|True|TRUE|yes|YES)$ ]]; then HISTORY_FLAG=""; fi

    echo "[Gitleaks] Running..."
    docker run --rm --user $(id -u):$(id -g) \
        -v "$PWD:/work:Z" -w /work \
        "$GITLEAKS_IMAGE" \
        detect --no-banner $HISTORY_FLAG -s /work \
        --report-format sarif --report-path - \
        > reports/gitleaks.sarif || true
    
    [ -f reports/gitleaks.sarif ] || touch reports/gitleaks.sarif
    
    GITLEAKS_COUNT=$(count_json_results "reports/gitleaks.sarif" "sarif")
    echo "[Gitleaks] findings: $GITLEAKS_COUNT"
else
    echo '[Gitleaks] skipped'
fi

# ==========================================
# 2. Semgrep
# ==========================================
if [[ "$SEMGREP_ENABLE" =~ ^(true|True|TRUE|yes|YES)$ ]]; then
    echo "[Semgrep] Running..."
    docker run --rm --user $(id -u):$(id -g) -e HOME=/tmp \
        -v "$PWD:/work:Z" -w /work \
        "$SEMGREP_IMAGE" \
        semgrep scan $SEMGREP_CFG_FLAGS $EXCLUDE_FLAGS \
          --sarif --max-target-bytes 0 --no-git-ignore $SCAN_TARGETS \
        > reports/semgrep.sarif || true
        
    [ -f reports/semgrep.sarif ] || touch reports/semgrep.sarif
    
    SEMGREP_COUNT=$(count_json_results "reports/semgrep.sarif" "sarif")
    echo "[Semgrep] findings: $SEMGREP_COUNT"
else
    echo '[Semgrep] skipped'
fi

# ==========================================
# 3. Bandit
# ==========================================
SCAN_ROOTS_QUOTED=$(echo "$SCAN_DIRS" | tr ',' ' ') # Simple space separation for find

if [[ "$BANDIT_ENABLE" =~ ^(true|True|TRUE|yes|YES)$ ]]; then
    # Check if python files exist
    HAS_PY=$(find $SCAN_ROOTS_QUOTED -type f -name '*.py' -print -quit | wc -l)
    
    if [ "$HAS_PY" -gt 0 ]; then
        echo '[Bandit] Python sources found — running bandit'
        # Format excludes for bandit (comma separated, no -x prefix yet)
        EXCLUDE_CSV=$(echo "$EXCLUDES" | tr -d ' ')
        BANDIT_EXCLUDE_ARG=""
        if [ -n "$EXCLUDE_CSV" ]; then BANDIT_EXCLUDE_ARG="-x $EXCLUDE_CSV"; fi

        docker run --rm \
            -v "$PWD:/work:Z" -w /work "$BANDIT_BASE_IMAGE" \
            bash -lc "python -m pip -q install --upgrade pip && \
                      python -m pip -q install --no-cache-dir bandit && \
                      bandit -q -r $SCAN_TARGETS $BANDIT_EXCLUDE_ARG -f json -o - || true" \
            > reports/bandit.json
            
        # Ensure file exists and is valid JSON
        if [ ! -s reports/bandit.json ]; then echo '{}' > reports/bandit.json; fi
        
        BANDIT_COUNT=$(count_json_results "reports/bandit.json" "bandit")
        echo "Bandit findings: $BANDIT_COUNT" > reports/bandit.txt
        echo "[Bandit] findings: $BANDIT_COUNT"
    else
        echo '[Bandit] no Python sources — skipping'
        echo 'Bandit skipped (no .py files)' > reports/bandit.txt
    fi
else
    echo '[Bandit] skipped'
    echo 'Bandit skipped by config' > reports/bandit.txt
fi

# ==========================================
# 4. Python Converter Script (Generic Format)
# ==========================================
echo "Running Python converter script inside Docker..."
docker run --rm --user $(id -u):$(id -g) \
    -v "$PWD:/work:Z" -w /work "$BANDIT_BASE_IMAGE" \
    python - <<'PY_SCRIPT'
import json, re
from pathlib import Path

SEV_MAP = {'error':'CRITICAL','warning':'MAJOR','note':'MINOR','none':'INFO',None:'INFO'}

def infer_type(rule, msg=''):
    tags=[]
    if isinstance(rule, dict):
        props=rule.get('properties',{}) or {}
        t=props.get('tags',[]) or props.get('category',[])
        if isinstance(t,str): t=[t]
        tags=t
    hay=(' '.join(tags)+' '+str(rule.get('name',''))+' '+str(rule.get('id',''))+' '+str(msg)).lower()
    if any(k in hay for k in ['security','xss','sqli','injection','crypto','auth','secret','leak','cwe-']):
        return 'VULNERABILITY'
    if any(k in hay for k in ['bug','defect','exception','npe','null pointer','off-by-one']):
        return 'BUG'
    return 'CODE_SMELL'

def norm_path(p):
    if not p: return None
    p = re.sub(r'^[a-zA-Z]+://', '', p)
    p = p.replace('file:','').replace('/work/','').lstrip('/')
    return p

def sarif_to_issues(pth):
    issues=[]
    try:
        data=json.loads(Path(pth).read_text(encoding='utf-8'))
    except Exception:
        return issues
    for run in data.get('runs',[]) or []:
        rule_by_id={}
        try:
            for r in (run.get('tool',{}).get('driver',{}).get('rules') or []):
                rid=r.get('id') or r.get('ruleId')
                if rid: rule_by_id[rid]=r
        except Exception: pass
        engine=(run.get('tool',{}).get('driver',{}).get('name') or 'external').lower()
        for res in (run.get('results') or []):
            rule_id = res.get('ruleId') or (res.get('rule') or {}).get('id') or 'rule'
            rule    = rule_by_id.get(rule_id,{})
            level   = str(res.get('level') or res.get('kind') or '').lower()
            severity= SEV_MAP.get(level,'INFO')
            msg     = ((res.get('message') or {}).get('text') or
                       (res.get('shortDescription') or {}).get('text') or
                       (rule.get('fullDescription') or {}).get('text') or
                       rule.get('name') or rule_id)
            locs    = res.get('locations') or res.get('relatedLocations') or [{}]
            pl      = (locs[0].get('physicalLocation') if locs else {}) or {}
            art     = pl.get('artifactLocation') or {}
            uri     = norm_path(art.get('uri') or art.get('uriBaseId') or '')
            region  = pl.get('region') or {}
            sl      = int(region.get('startLine') or 1)
            sc      = int(region.get('startColumn') or 1)
            el      = int(region.get('endLine') or sl)
            ec      = int(region.get('endColumn') or sc)
            issues.append({
                'engineId': engine,
                'ruleId': str(rule_id),
                'severity': severity,
                'type': infer_type(rule, msg),
                'primaryLocation': {
                    'message': (msg or '')[:4000],
                    'filePath': uri or '',
                    'textRange': {'startLine': sl, 'endLine': el, 'startColumn': sc, 'endColumn': ec}
                }
            })
    return issues

def bandit_to_issues(pth):
    issues=[]
    try:
        data=json.loads(Path(pth).read_text(encoding='utf-8'))
    except Exception:
        return issues
    for r in data.get('results',[]) or []:
        sev=str(r.get('issue_severity') or 'LOW').upper()
        sev_map={'LOW':'MINOR','MEDIUM':'MAJOR','HIGH':'CRITICAL'}
        severity=sev_map.get(sev,'INFO')
        msg=r.get('issue_text') or r.get('test_name') or 'Bandit issue'
        path=norm_path(r.get('filename'))
        sl=int(r.get('line_number') or 1)
        issues.append({
            'engineId':'bandit',
            'ruleId': str(r.get('test_id') or r.get('test_name') or 'BANDIT'),
            'severity': severity,
            'type': 'VULNERABILITY',
            'primaryLocation': {
                'message': (msg or '')[:4000],
                'filePath': path or '',
                'textRange': {'startLine': sl, 'endLine': sl, 'startColumn': 1, 'endColumn': 1}
            }
        })
    return issues

out={'issues':[]}
for p in ['reports/semgrep.sarif','reports/gitleaks.sarif','reports/bandit.json']:
    fp=Path(p)
    if not fp.exists(): continue
    if fp.suffix.lower()=='.sarif':
        out['issues'].extend(sarif_to_issues(str(fp)))
    elif fp.name=='bandit.json':
        out['issues'].extend(bandit_to_issues(str(fp)))

seen=set(); dedup=[]
for i in out['issues']:
    loc=i.get('primaryLocation',{})
    key=(i.get('engineId'), i.get('ruleId'), loc.get('filePath'), (loc.get('textRange') or {}).get('startLine'))
    if key in seen: continue
    seen.add(key); dedup.append(i)
out['issues']=dedup

print(f"DEBUG: Saving to reports/external-issues.json, found {len(out['issues'])} issues")
Path('reports').mkdir(parents=True, exist_ok=True)
Path('reports/external-issues.json').write_text(json.dumps(out, ensure_ascii=False), encoding='utf-8')
print(f'[converter] Wrote reports/external-issues.json with {len(out["issues"])} issues')
PY_SCRIPT

# ==========================================
# 5. Failure Logic
# ==========================================
FAILED=0
FAIL_MSG=""

if [[ "$GITLEAKS_ENABLE" =~ ^(true|True|TRUE|yes|YES)$ ]] && \
   [[ "$GITLEAKS_FAIL_ON_FINDING" =~ ^(true|True|TRUE|yes|YES)$ ]] && \
   [ "$GITLEAKS_COUNT" -gt 0 ]; then
    FAIL_MSG="${FAIL_MSG}Gitleaks=${GITLEAKS_COUNT} "
    FAILED=1
fi

if [[ "$SEMGREP_ENABLE" =~ ^(true|True|TRUE|yes|YES)$ ]] && \
   [[ "$SEMGREP_FAIL_ON_FINDING" =~ ^(true|True|TRUE|yes|YES)$ ]] && \
   [ "$SEMGREP_COUNT" -gt 0 ]; then
    FAIL_MSG="${FAIL_MSG}Semgrep=${SEMGREP_COUNT} "
    FAILED=1
fi

if [[ "$BANDIT_ENABLE" =~ ^(true|True|TRUE|yes|YES)$ ]] && \
   [[ "$BANDIT_FAIL_ON_FINDING" =~ ^(true|True|TRUE|yes|YES)$ ]] && \
   [ "$BANDIT_COUNT" -gt 0 ]; then
    FAIL_MSG="${FAIL_MSG}Bandit=${BANDIT_COUNT} "
    FAILED=1
fi

if [ "$FAILED" -eq 1 ]; then
    echo "Static Security Checks failed: ${FAIL_MSG}"
    exit 1
else
    echo "Static Security Checks completed: gitleaks=${GITLEAKS_COUNT}, semgrep=${SEMGREP_COUNT}, bandit=${BANDIT_COUNT}"
    exit 0
fi