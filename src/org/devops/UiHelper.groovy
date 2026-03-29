package org.devops

class UiHelper implements Serializable {

    static final String pipelineModeDesc =
    'Default=ใช้ค่าจาก config | full=build+deploy | build-only=build | build-deploy=build+deploy | deploy-only=deploy(tag เดิม)'

    static final String deployEnvDesc =
        'Default=ใช้ค่าจาก config | test/dev/poc=non-production | uat=user acceptance | prd=production'

    static final String forceRebuildDesc =
        'Default=ใช้ค่าจาก config | Yes=--no-cache | No=ใช้ cache'

    static final String deployRegistryDesc =
        'Default=ใช้ค่าจาก config | gitlab=GitLab Registry | docker-registry=Internal Registry'

    static final String deployStrategyDesc =
        'Default=ใช้ค่าจาก config | default=rolling update | canary=canary+DAST'

    static final String scalingStrategyDesc =
        'Default=ใช้ค่าจาก config | hpa=Horizontal (แนะนำ prd) | vpa=Vertical | keda=Event-driven'

    static String gateMessage(String title, List failedTools, String plainHint = '') {
        def lines = []
        lines << title
        lines << '───────────────────────────────────'
        failedTools.each { lines << "  ❌ ${it}" }
        if (plainHint) lines << plainHint
        lines << '───────────────────────────────────'
        lines << '▶ Abort   → หยุด pipeline (แนะนำ)'
        lines << '▶ Proceed → ข้าม — pipeline จะเป็น UNSTABLE'
        return lines.join('\n')
    }
}