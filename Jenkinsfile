/*test-python-backend-api*/                                                                                                                                           
/* groovylint-disable-next-line UnusedVariable */
@Library('devops-assignment-test@jenkins-shared-library') _
                                                                                                                                                                   
def projectZoneName ='Devops-Assignment'                                                                                                                                
def appName = 'test-be-api'                                                                                                                             
def prometheusHostUrl = env.PROMETHEUS_HOST_URL                                                                                                                     
                                                                                                                                                                    
def cfg = [                                                                                                                                                         
  projectZoneName : projectZoneName,                                                                                                                                
  projectName : appName,                                                                                                                                            
  language    : 'python',                                                                                                                                           
  projectType : 'backend',                                                                                                                                          
  imageEnv    : 'prd',                                                                                                                                              
                                                                                                                                                                    
  ci: [                                                                                                                                                             
    pipelineMode: 'build-deploy-all',                                                                                                                               
    forceRebuild: 'No',                                                                                                                                             
    deployRegistry: 'gitlab',                                                                                                                                       
    autoSync: 'No',                                                                                                                                                 
    deployStrategy: 'canary',                                                                                                                                       
    kubeHealthCheck: 'No',                                                                                                                                          
    analysis: 'No',                                                                                                                                                 
    autoScaling: 'vpa',                                                                                                                                             
    dastScan: 'No',                                                                                                                                                 
    enableLogPvc: 'No'                                                                                                                                              
  ],                                                                                                                                                                              
                                                                                                                                                                    
  gitlab: [                                                                                                                                                         
    baseUrl      : 'https://gitlab.com',                                                                                                                  
    registryHost : 'registry.gitlab.com',                                                                                                                     
    sourcePath   : 'sittidet.jo/devops-assignment-test',                                                                                                                
    manifestPath : 'sittidet.jo/devops-assignment-test',                                                                                                                
    sourceBranch : 'test-python-backend-api'                                                                                                                                           
  ],                                                                                                                                                                
                                                                                                                                                                    
  deployment: [                                                                                                                                                     
    namespace       : 'default',                                                                                                                                    
    containerPort   : 10104,                                                                                                                                        
    targetPort      : 10104,                                                                                                                                        
    nodePort        : 30021,                                                                                                                                        
    imagePullSecret : 'test-pull-secret',                                                                                                                           
    envType       : 'config.yaml', // env, configMap                                                                                                                
    secretName    : 'test-config-api',                                                                                                                              
    // configMapName : 'config-map-backend-sample-app',                                                                                                             
    volumeMounts  : [                                                                                                                                               
      [ name: 'config-volume', mountPath: '/app/config.yaml', subPath: 'config.yaml' ]                                                                              
      // [ name: 'config-volume', mountPath: '/app/logging.yaml', subPath: 'logging.yaml' ]                                                                         
    ],                                                                                                                                                              
    extraEnvFrom  : [                                                                                                                                               
      // [ configMapRef: 'config-map-backend-sample-app' ],                                                                                                         
      // [ secretRef   : 'backend-sample-app-secret' ]                                                                                                              
    ],                                                                                                                                                              
                                                                                                                                                                    
    logPvc: [                                                                                                                                                       
        storage     : '2Gi',                                                                                                                                        
        storageClass: 'longhorn',                                                                                                                                   
        accessMode  : 'ReadWriteMany',                                                                                                                              
        mounts: [                                                                                                                                                   
            [ mountPath: '/app/logs',    subPath: 'logs'    ],                                                                                                      
            // [ mountPath: '/app/uploads', subPath: 'uploads' ],                                                                                                   
        ],                                                                                                                                                          
    ],                                                                                                                                                              
                                                                                                                                                                    
    cpuRequest      : '350m',                                                                                                                                       
    cpuLimit        : '750m',                                                                                                                                       
    memRequest      : '256Mi',                                                                                                                                      
    memLimit        : '512Mi',                                                                                                                                      
    // progressDeadlineSeconds: 120,                                                                                                                                
    startupProbe: [                                                                                                                                                 
      path: '/health',                                                                                                                                              
      port: 10104,                                                                                                                                                  
      failureThreshold: 20,                                                                                                                                         
      periodSeconds: 6,                                                                                                                                             
      timeoutSeconds: 5                                                                                                                                             
    ],                                                                                                                                                              
    readinessProbe: [                                                                                                                                               
      path: '/health',                                                                                                                                              
      port: 10104,                                                                                                                                                  
      initialDelaySeconds: 5,                                                                                                                                       
      periodSeconds: 10,                                                                                                                                            
      timeoutSeconds: 5                                                                                                                                             
    ],                                                                                                                                                              
    livenessProbe: [                                                                                                                                                
      path: '/health',                                                                                                                                              
      port: 10104,                                                                                                                                                  
      initialDelaySeconds: 30,                                                                                                                                      
      periodSeconds: 20,                                                                                                                                            
      timeoutSeconds: 5                                                                                                                                             
    ]                                                                                                                                                               
  ],                                                                                                                                                                
                                                                                                                                                                    
  cronjobs: [                                                                                                                                                       
    [                                                                                                                                                               
      name: 'smoke-test-task',                                                                                                                                      
      schedule: '*/1 * * * *',                                                                                                                                      
      command: ['python', 'scripts/cronjob_smoke_test.py'],                                                                                                         
      env: [                                                                                                                                                        
        BASE_URL: 'http://test-python-backend-api-service-stable-prd:10104'                                                                                         
      ]                                                                                                                                                             
    ],                                                                                                                                                              
    // [                                                                                                                                                            
    //   name: 'sync-data',                                                                                                                                         
    //   schedule: '*/15 * * * *',                                                                                                                                  
    //   command: ['python', 'scripts/sync.py']                                                                                                                     
    // ]                                                                                                                                                            
  ],                                                                                                                                                                
                                                                                                                                                                    
  keda: [                                                                                                                                                           
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
      ],                                                                                                                                                            
      // [                                                                                                                                                          
      //   type: 'prometheus',                                                                                                                                      
      //   metadata: [                                                                                                                                              
      //     serverAddress: prometheusHostUrl,                                                                                                                      
      //     query: "sum(rate(http_requests_total{app=\"${appName}\"}[2m]))",                                                                                       
      //     threshold: '50'                                                                                                                                        
      //   ]                                                                                                                                                        
      // ]                                                                                                                                                          
    ]                                                                                                                                                               
  ],                                                                                                                                                                
                                                                                                                                                                    
  hpa: [                                                                                                                                                            
    minReplicas: 2,                                                                                                                                                 
    maxReplicas: 6,                                                                                                                                                 
    cpuPercent: 75,                                                                                                                                                 
    memPercent: 75                                                                                                                                                  
  ],                                                                                                                                                                
                                                                                                                                                                    
  vpa: [                                                                                                                                                            
    mode: 'Off'                                                                                                                                                     
  ],                                                                                                                                                                
                                                                                                                                                                    
  rollout: [                                                                                                                                                        
    enabled: true,                                                                                                                                                  
    autoPromotionEnabled: false,                                                                                                                                    
    steps: [                                                                                                                                                                                                                                                                                                      
      [setWeight: 25],                                                                                                                                              
      [pause: [duration: '15s']],                                                                                                                                   
      [analysis: ["${appName}-analysis-newman"]],                                                                                                                   
      [setWeight: 50],                                                                                                                                              
      [analysis: ["${appName}-analysis-newman", "${appName}-analysis-prometheus"]],                                                                                 
      [pause: [duration: '15s']],                                                                                                                                   
      [setWeight: 100]                                                                                                                                              
    ],                                                                                                                                                              
                                                                                                                                                                    
    smoke: [                                                                                                                                                        
      secretName: "test-api-secret",                                                                                                                                
      testFilePath: "tests/smoke-test.json",                                                                                                                        
      timeout: "10",                                                                                                                                                
      count: 3,                                                                                                                                                     
      interval: "5s",                                                                                                                                               
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
    sourceCodePath : '.',                                                                                                                                           
    dockerfilePath : '.',                                                                                                                                           
  ],                                                                                                                                                                
                                                                                                                                                                    
  credentials: [                                                                                                                                                    
    gitlab       : 'GITLAB_CREDENTIALS_TEST',                                                                                                                            
    gitlab_text  : 'GITLAB_PAT_CREDENTIALS_TEST',                                                                                                                        
    scm          : 'GITLAB_CREDENTIALS',                                                                                                                            
    backend_env  : 'BACKEND_ENV',                                                                                                                                   
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