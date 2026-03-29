// vars/sbom.groovy
def call(Map cfg = [:]) {
  def image = (cfg.image ?: error('[sbom] image is required'))

  def spdxOut = (cfg.spdxOutput ?: 'sbom.spdx.json')
  def cdxOut  = (cfg.cdxOutput  ?: 'sbom.cyclonedx.json')

  echo "[sbom] Generating SBOM for local image: ${image}"
  echo "[sbom]  - SPDX        -> ${spdxOut}"
  echo "[sbom]  - CycloneDX   -> ${cdxOut}"

  def proxyEnv = []
  ['HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'no_proxy'].each { k ->
    if (env[k]) proxyEnv << "${k}='${env[k]}'"
  }

  def proxyExport = proxyEnv ? "export ${proxyEnv.join(' ')}" : "echo '[sbom] No proxy env configured'"

  try {
    sh """#!/usr/bin/env bash
      set -euo pipefail

      ${proxyExport}

      echo "[sbom] Generating SPDX SBOM with Trivy..."
      trivy image \\
        --format spdx-json \\
        --output ${spdxOut} \\
        "${image}"

      echo "[sbom] Generating CycloneDX SBOM with Trivy..."
      trivy image \\
        --format cyclonedx \\
        --output ${cdxOut} \\
        "${image}"

      echo "[sbom] Generated SBOM files:"
      ls -lh ${spdxOut} ${cdxOut} || true

      echo "[sbom] SPDX preview:"
      head -c 200 "${spdxOut}" || true
      echo
      echo "[sbom] CycloneDX preview:"
      head -c 200 "${cdxOut}" || true
      echo
    """
  } catch (e) {
    echo "[sbom] Failed to generate SBOM for ${image}"
    error(e.toString())
  }

  archiveArtifacts artifacts: "${spdxOut},${cdxOut}"
}
