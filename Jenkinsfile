/*frontend-sample-app*/
/* groovylint-disable-next-line UnusedVariable */
@Library('my-devops-library@feat/newman') _

def appName = 'test-jenkins-app'
def prometheusHostUrl = env.PROMETHEUS_HOST_URL

def cfg = [
  projectName : appName,
  language    : 'javascript',
  projectType : 'frontend',
  imageEnv    : 'dev',

  ci: [
    forceRebuild: 'No',
    deployRegistry: 'gitlab',
    deployStrategy: 'canary',
    autoSync: 'Yes',
    kubeHealthCheck: 'Yes',
    analysis: 'Yes',
    autoScaling: 'keda',
    dastScan: 'No'
  ],

  gitlab: [
    baseUrl      : 'https://skywalker.inet.co.th',
    registryHost : 'skywalker.inet.co.th:5050',
    sourcePath   : 'devops/test-jenkins-app',
    manifestPath : 'devops/test-jenkins-app',
    sourceBranch : 'main'
  ],

  deployment: [
    namespace       : 'default',
    containerPort   : 8080,
    targetPort      : 8080,
    nodePort        : 30001,
    imagePullSecret : 'test-pull-secret',
    envType         : 'none',
    env: [
      HOSTNAME: '0.0.0.0',
      PORT: '8080'
    ],
    cpuRequest      : '250m',
    cpuLimit        : '1000m',
    memRequest      : '512Mi',
    memLimit        : '1Gi',
    startupProbe: [
      path: '/',
      port: 8080,
      failureThreshold: 30,
      periodSeconds: 10,
      timeoutSeconds: 5
    ],
    readinessProbe: [
      path: '/',
      port: 8080,
      initialDelaySeconds: 5,
      periodSeconds: 5,
      timeoutSeconds: 5
    ],
    livenessProbe: [
      path: '/',
      port: 8080,
      initialDelaySeconds: 30,
      periodSeconds: 10,
      timeoutSeconds: 5
    ]
  ],

  keda: [
    enabled: true,
    minReplicas: 2,
    maxReplicas: 6,
    pollingInterval: 30,
    cooldownPeriod: 300,

    advanced: [
      horizontalPodAutoscalerConfig: [
        behavior: [
          scaleUp: [
            stabilizationWindowSeconds: 0,
            policies: [
              [type: 'Pods', value: 2, periodSeconds: 60],
              [type: 'Percent', value: 25, periodSeconds: 60]
            ]
          ],
          scaleDown: [
            stabilizationWindowSeconds: 300,
            policies: [
              [type: 'Pods', value: 1, periodSeconds: 60]
            ]
          ]
        ]
      ]
    ],

    triggers: [
      [
        type: 'cpu',
        metricType: 'Utilization',
        metadata: [
          value: '75'
        ]
      ],
      [
        type: 'memory',
        metricType: 'Utilization',
        metadata: [
          value: '75'
        ]
      ]
    ]
  ],

  hpa: [
    minReplicas: 2,
    maxReplicas: 10,
    cpuPercent: 75,
    memPercent: 75
  ],

  vpa: [
    mode: 'Auto'
  ],

  rollout: [
    enabled: true,
    autoPromotionEnabled: false,
    steps: [
      [setWeight: 25],
      [pause: [duration: '15s']],
      [analysis: "${appName}-analysis-prometheus"],
      [setWeight: 50],
      [analysis: "${appName}-analysis-prometheus"],
      [pause: [:]],
      [setWeight: 100]
    ],

    smoke: [
      paths: [
        'GET|/'
      ],
      timeout: "10",
      count: 3,
      smoke_interval: "5s",
      failureLimit: 1
    ],

    promHealth: [
        window: '5m',
        interval: '30s',
        count: 4,
        failureLimit: 1
    ],

    promHealthPerWeight: [
        '25': [ window: '2m' ],
        '50': [ window: '5m' ]
    ],

    analysis: [
      successfulRunHistoryLimit: 1,
      unsuccessfulRunHistoryLimit: 1
    ]
  ],

  build: [
    sourceCodePath  : '.',
    dockerfilePath  : '.',
  ],

  credentials: [
    gitlab       : 'GITLAB_CREDENTIALS',
    gitlab_text  : 'GITLAB_PAT_CREDENTIALS',
    scm          : 'GITLAB_CREDENTIALS',
    frontend_env : 'FRONTEND_ENV',
    argocd       : 'ARGOCD_CREDENTIALS'
  ],

  env: [
    IMAGE_REGISTRY      : env.IMAGE_REGISTRY,
    JENKINS_HOST_URL    : env.JENKINS_HOST_URL,
    ARGOCD_HOST_URL     : env.ARGOCD_HOST_URL,
    PROMETHEUS_HOST_URL : prometheusHostUrl,
  ]
]

generalPipeline(cfg)
