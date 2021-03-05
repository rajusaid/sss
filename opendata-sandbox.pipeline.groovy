/**
 * author: a.g.subramanian
 * Process the baseline and migrate to Sandbox
 */

def opendataList
def opendataComponentList
microserviceList = []
apiList = []
cqlList = []
mConfigList = []
mStubList = []
repoList = []
tagName = ""

def executeSetp(releaseName,component,zipRepo=false) {
    def componentName = component['name']
    def componentVer = component['version']
    def componentVerWithoutPrefix = componentVer.replaceAll("[A-Za-z]","")
    if(componentName == "ob-api-opendataconfig") {
        zipRepo = true
    }
    cloneGitRepo(componentName,componentVer)
    if(zipRepo){
         zip zipFile: "${componentName}-${componentVerWithoutPrefix}.zip", archive: false, dir: "$WORKSPACE/$componentName"
         mConfigList << "${componentName}-${componentVerWithoutPrefix}.zip"
    } else {
        def type = findComponentType(componentName)
        if(type == "API") {
            downloadAPIZip(releaseName,componentName,componentVerWithoutPrefix)
        } else if(type == "MICROSERVICE") {
            compressCQLZip(releaseName,componentName,componentVerWithoutPrefix)
            getMicroserviceImage(releaseName,componentName,componentVerWithoutPrefix)
        }
    }
    removeGitRepo(componentName)
}

def convertTextToYaml(releaseFile) {
  def affected = false
  componentInYamlFormat=[:]
  parentComponent= []
  affectParentComponent=[] 
  releaseFile.split("\n").each {
      componentInArray=it.split(" ")
      componentList= [:]
          if(componentInArray[0].matches("ob-(.*)")) {
              if(!affected) {
                componentList.put("name",componentInArray[0].trim())
                componentList.put("version",componentInArray[1].trim())
                parentComponent.add(componentList)
              } else {
                componentList.put("name",componentInArray[0].trim())
                componentList.put("version",componentInArray[1].trim())
                affectParentComponent.add(componentList)                      
              }
          } else if(componentInArray[0].startsWith("=====")) {
             affected= true
          }
      }
  componentInYamlFormat.put("COMPONENTS",parentComponent)
  componentInYamlFormat.put("AFFECTED_COMPONENTS",affectParentComponent)
  return componentInYamlFormat
}

def findComponentType(componentName) {
    def componentType
    switch(componentName) {
        case ~/^ob-us-(.*)$/:
            componentType="MICROSERVICE"
            break;
        case ~/^ob-api-(.*)$/:
            componentType="API"
            break;
        case ~/^ob-bat-(.*)$/:
            componentType="MICROSERVICE"
            break;
        default:
            break
    }    
    return componentType
}

def getMicroserviceImage(releaseName,componentName,componentVer) {
    appFile = fileExists file: "$WORKSPACE/$componentName/src/main/resources/application.properties"
    if(appFile) {
        def props = readProperties  file: "$WORKSPACE/$componentName/src/main/resources/application.properties"
        microserviceList << releaseName+"~"+props['spring.application.name']+":"+componentVer
    }
}

def cloneGitRepo(componentName,componentVer) {
    println "Cloning: " + componentName
    checkout([$class: 'GitSCM',branches: [[name: componentVer ]],doGenerateSubmoduleConfigurations: false,
    extensions: [[$class: 'CloneOption', timeout: 10, noTags: false, shallow: true],[$class: 'RelativeTargetDirectory', relativeTargetDir: componentName]],
    gitTool: 'Default',
    submoduleCfg: [],userRemoteConfigs: [[url: 'ssh://jenkins@devops.obphoenix.co.uk:29418/'+componentName,credentialsId: 'adop-jenkins-master']]   ])
}

def compressCQLZip(releaseName,componentName,componentVer) {
    def cqlFolder = new File("$WORKSPACE/$componentName/src/main/resources/dbscripts")
    if( cqlFolder.exists() ) {
         zip zipFile: "${componentName}-${componentVer}.zip", archive: false, dir: "$WORKSPACE/$componentName/src/main/resources/dbscripts"
         cqlList << "${releaseName}~${componentName}-${componentVer}.zip"
    }
}

def compressRepo(componentName,repoTar) {
    dir("$WORKSPACE/$componentName") {
        sh "tar -cvf $repoTar ." 
        sh "mv $repoTar $WORKSPACE/"
    }
}

def removeGitRepo(componentName) {
    dir("${componentName}@tmp") {
        deleteDir()
    }
    dir("${componentName}") {
        deleteDir()
    }
}

def downloadAPIZip(releaseName,componentName,componentVer) {
    httpRequest authentication: 'NEXUS_CREDENTIAL', ignoreSslErrors: true, responseHandle: 'NONE', url: env.APIGEE_PROXY_NEXUS_URL+"/$componentName/${componentVer}.zip", validResponseCodes: '200', outputFile: "$componentName-${componentVer}.zip"
    apiList << "${releaseName}~${componentName}-${componentVer}.zip"
}

def downloadImage(componentNameWithVer,imageName) {
    withDockerServer([uri: 'tcp://192.168.1.101:2379']) {
        withDockerRegistry([credentialsId: 'NEXUS_REGISTRY_CREDENTIAL', url: 'http://'+NEXUS_REGISTRY_URL]) { 
            docker.image("devops.obphoenix.co.uk:8083/openbanking/$componentNameWithVer").pull()
        }
    } 
}

def tarImage(componentNameWithVer,imageName) {
    withDockerServer([uri: 'tcp://192.168.1.101:2379']) {
             sh "docker save devops.obphoenix.co.uk:8083/openbanking/$componentNameWithVer > /tmp/${imageName}.tar"        
    } 
}

def migrateToS3(type,path) {
    today=new Date().format('ddMMyyyy')    
    sh "aws s3 cp $path s3://ob-opendata-artifacts-eu-west-2/OpenData-2.2-${today}-${tagName}/$type --acl bucket-owner-full-control --sse aws:kms --sse-kms-key-id arn:aws:kms:eu-west-2:454579647685:key/f332175c-44c4-4998-a620-42b9d111da68"
}

pipeline {
    agent {
        label "any"
    }
    
    stages {
        
      stage('Git-Checkout') {
        steps {
          script {
            tagName = GERRIT_REFNAME.replaceAll("refs/tags/","")
            cleanWs()
            checkout([$class: 'GitSCM',branches: [[name: "$tagName"]],doGenerateSubmoduleConfigurations: false,
            extensions: [],
            gitTool: 'Default',
            submoduleCfg: [],userRemoteConfigs: [[url: 'ssh://jenkins@devops.obphoenix.co.uk:29418/ob-baseline-release',credentialsId: 'adop-jenkins-master']]   ])
          }
        }
      }
      
      stage('Reading baseline file') {
        steps {
            script {
              _opendataFile = readFile file:"$WORKSPACE/OBIE-OpenData.txt"
              opendataList = convertTextToYaml(_opendataFile)
              opendataComponentList = opendataList.COMPONENTS   
            }
         }
      }

      stage("Migrate Baseline") {
          steps {
              script {
                    cloneGitRepo("ob-baseline-release",tagName)
                    repoTar =  "ob-baseline-release-"+tagName+".tar"
                    repoList << repoTar
                    compressRepo("ob-baseline-release",repoTar)   
                    removeGitRepo("ob-baseline-release")               
                }
           }
       }

      stage("Migrate OpenData") {
          steps {
              script {
                    opendataComponentList.each { component ->
                       executeSetp("",component)
                     }
                 }
             }
         }

        stage("archiveArtifacts") {
            steps {
                script {
                    println "archiveArtifacts"
                }
            archiveArtifacts artifacts: '**', fingerprint: true
            }
        }

        stage("Download Microservice Image") {
         agent {
             label 's3testslave'
         }

         steps {
            script {
                    microserviceList.each {
                        beforeImageName = it.split("~")
                        imageName = beforeImageName[1].replaceAll(":","-")
                        downloadImage(beforeImageName[1],imageName)
                        tarImage(beforeImageName[1],imageName)
                    }
                }
            }
        }  

        stage("Migrate to S3") {
         agent {
             label 's3testslave'
         }

         steps {
            script {
                    copyArtifacts filter: '**', fingerprintArtifacts: true, projectName: JOB_BASE_NAME, selector: specific(BUILD_NUMBER)

                    println repoList
                    repoList.each {
                        migrateToS3("REPO",it)
                    }

                    println cqlList
                    cqlList.each {
                        cqlName =  it.split("~")
                        migrateToS3("CQL/${cqlName[0]}",cqlName[1])
                    }
                    
                    println microserviceList
                    microserviceList.each {
                        beforeImageName = it.split("~")
                        migrateToS3("MICROSERVICE/${beforeImageName[0]}","/tmp/"+beforeImageName[1].replaceAll(":","-")+".tar")
                    }
                    
                    println apiList
                    apiList.each {
                        apiZipName = it.split("~")
                        migrateToS3("APIs/${apiZipName[0]}",apiZipName[1])
                    }
                    
                    println mConfigList
                    mConfigList.each {
                        migrateToS3("CONFIG",it)
                    }
                    
                    println mStubList
                    mStubList.each {
                        migrateToS3("STUB",it)
                    }
                }
            }
        } 

    }
}