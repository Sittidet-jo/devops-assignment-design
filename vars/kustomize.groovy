#!/usr/bin/env groovy
import org.devops.kustomize.*

def call(Map config) {
  echo "[INFO] Start Kustomize Pipeline for ${config.projectName ?: '(unknown project)'}"

  def v = new Validation(this)
  v.applyDefaults(config)
  def rolloutEnabled = v.isRolloutEnabled(config)

  def gitOps = new GitOps(this)
  gitOps.cloneAndCheckout(config)

  def baseGen = new BaseGenerator(this)
  baseGen.ensureBase(config, rolloutEnabled)

  if (!config.rollout && config.keySet().any { it.toString().startsWith('rollout.') }) {
    def rolloutMap = [:]
    config.each { k, val ->
      if (k.toString().startsWith('rollout.')) {
        def subKey = k.toString().replaceFirst(/^rollout\./, '')
        rolloutMap[subKey] = val
      }
    }
    config.rollout = rolloutMap
  }

  def overlay = new OverlayUpdater(this)

  boolean deployApp  = config.doDeploy == true
  boolean deployCron = (config.deployCronJobs == 'Yes') || (config.doCronOnly == true)

  if (deployApp && deployCron) {
    echo "[INFO] Updating both app and cronjob overlays..."
    overlay.updateOverlay(config, rolloutEnabled)

    def cronConfig = config.clone()
    cronConfig.doCronOnly  = true
    cronConfig.doDeploy    = false
    overlay.updateOverlay(cronConfig, rolloutEnabled)

  } else {
    overlay.updateOverlay(config, rolloutEnabled)
  }

  echo "[SUCCESS] Kustomize generation for ${config.projectName} (${config.deployEnv}) completed."
}