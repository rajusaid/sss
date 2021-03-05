/**
 * author: a.g.subramanian
 * Process the baseline and migrate to SFTP
 */

def r2RubyList
def r3TopazList
def r4OnyxList
def r41JadeList
def commonList
def configList
def stubList
def toolList
def r2RubyComponentList
def r3TopazComponentList
def r4OnyxComponentList
def r41JadeComponentList
def commonComponentList
def configComponentList
def stubComponentList
def toolComponentList
microserviceList = []
apiList = []
cqlList = []
mConfigList = []
mStubList = []
repoList = []
tagName = ""

def executeSetp(component) {
    def componentName = component['name']
    def componentVer = component['version']
    def componentVerWithoutPrefix = componentVer.replaceAll("[A-Za-z]","")
    cloneGitRepo(componentName,componentVer)
    repoTar =  componentName+"-"+componentVerWithoutPrefix+".tar"
    repoList << repoTar
    compressRepo(componentName,repoTar)
    def type = findComponentType(componentName)
    if(type == "API") {
        downloadAPIZip(componentName,componentVerWithoutPrefix)
    } else if(type == "MICROSERVICE") {
        compressCQLZip(componentName,componentVerWithoutPrefix)
        getMicroserviceImage(componentName,componentVerWithoutPrefix)
    }
    removeGitRepo(componentName)
}

def compressRepo(componentName,repoTar) {
    dir("$WORKSPACE/$componentName") {
        sh "tar -cvf $repoTar ." 
        sh "mv $repoTar $WORKSPACE/"
    }
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

def getMicroserviceImage(componentName,componentVer) {
    appFile = fileExists file: "$WORKSPACE/$componentName/src/main/resources/application.properties"
    if(appFile) {
        def props = readProperties  file: "$WORKSPACE/$componentName/src/main/resources/application.properties"
        microserviceList << props['spring.application.name']+":"+componentVer
    }
}

def cloneGitRepo(componentName,componentVer) {
    println "Cloning: " + componentName
    checkout([$class: 'GitSCM',branches: [[name: componentVer ]],doGenerateSubmoduleConfigurations: false,
    extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: componentName]],
    gitTool: 'Default',
    submoduleCfg: [],userRemoteConfigs: [[url: 'ssh://jenkins@gerrit:29418/'+componentName,credentialsId: 'adop-jenkins-master']]   ])
}

def compressCQLZip(componentName,componentVer) {
    def cqlFolder = new File("$WORKSPACE/$componentName/src/main/resources/dbscripts")
    if( cqlFolder.exists() ) {
         zip zipFile: "${componentName}-${componentVer}.zip", archive: false, dir: "$WORKSPACE/$componentName/src/main/resources/dbscripts"
         cqlList << "${componentName}-${componentVer}.zip"
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

def downloadAPIZip(componentName,componentVer) {
    httpRequest authentication: 'NEXUS_CREDENTIAL', ignoreSslErrors: true, responseHandle: 'NONE', url: env.APIGEE_PROXY_NEXUS_URL+"/$componentName/${componentVer}.zip", validResponseCodes: '200', outputFile: "$componentName-${componentVer}.zip"
    apiList << "${componentName}-${componentVer}.zip"
}

def downloadImage(componentNameWithVer) {
    withDockerServer([uri: 'tcp://192.168.2.238:2379']) {
        withDockerRegistry([credentialsId: 'NEXUS_REGISTRY_CREDENTIAL', url: 'http://'+NEXUS_REGISTRY_URL]) { 
            docker.image("devops.obphoenix.co.uk:8083/openbanking/$componentNameWithVer").pull()
        }
    } 
}

def tarImage(componentNameWithVer,imageName) {
    withDockerServer([uri: 'tcp://192.168.2.238:2379']) {
             sh "docker save devops.obphoenix.co.uk:8083/openbanking/$componentNameWithVer > /tmp/${imageName}.tar"        
    } 
}

def migrateToSFTP(type,path) {
    sh "sudo mv $path /data/sftp/obphoenix/OpenBankingMigration/$tagName/$type/"
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
            submoduleCfg: [],userRemoteConfigs: [[url: 'ssh://jenkins@gerrit:29418/ob-baseline-release',credentialsId: 'adop-jenkins-master']]   ])
          }
        }
      }
      
      stage('Reading baseline file') {
        steps {
            script {
              _r2RubyFile = readFile file:"$WORKSPACE/OBIE1.1-R2-Ruby.txt"
              r2RubyList = convertTextToYaml(_r2RubyFile)
              r2RubyComponentList = r2RubyList.AFFECTED_COMPONENTS

              _r3TopazFile = readFile file:"$WORKSPACE/OBIE2.0-R3-Topaz.txt"
              r3TopazList = convertTextToYaml(_r3TopazFile)
              r3TopazComponentList = r3TopazList.AFFECTED_COMPONENTS
                
              _r41JadeFile = readFile file:"$WORKSPACE/OBIE3.1-R4-Jade.txt"
              r41JadeList = convertTextToYaml(_r41JadeFile)
              r41JadeComponentList = r41JadeList.AFFECTED_COMPONENTS
              
              _commonFile = readFile file:"$WORKSPACE/OBIECommon.txt"
              commonList = convertTextToYaml(_commonFile)
              commonComponentList = commonList.AFFECTED_COMPONENTS

              _configFile = readFile file:"$WORKSPACE/OBIE-Config.txt"
              configList = convertTextToYaml(_configFile)
              configComponentList = configList.AFFECTED_COMPONENTS       

              _stubFile = readFile file:"$WORKSPACE/OB-Stubs.txt"
              stubList = convertTextToYaml(_stubFile)
              stubComponentList = stubList.COMPONENTS    

               _toolFile = readFile file:"$WORKSPACE/OB-Tools.txt"
              toolList = convertTextToYaml(_toolFile)
              toolComponentList = toolList.AFFECTED_COMPONENTS                
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

      stage("Migrate R2") {
          steps {
              script {
                    r2RubyComponentList.each { component ->
                       executeSetp(component)
                     }
                 }
             }
         }

      stage("Migrate R3") {
          steps {
              script {
                    r3TopazComponentList.each { component ->
                        executeSetp(component)
                     }
                 }
             }
         }

        stage("Migrate R4_1") {
            steps {
            script {
                    r41JadeComponentList.each { component ->
                        executeSetp(component)
                    }
                }
            }
        }        
        
        stage("Migrate Versionless") {
            steps {
            script {
                    commonComponentList.each { component ->
                        executeSetp(component)
                    }
                }
            }
        }  

        stage("Migrate STUB") {
            steps {
                 script {
                    stubComponentList.each { component ->
                        if(component['name'] == "ob-us-auth") {
                            executeSetp(component)
                        }
                    }
                }
            }
        }  

        stage("Migrate Config") {
          steps {
            script {
                    configComponentList.each { component ->
                        if(component['name'] == "ob-app-config") {
                            return 
                        }
                        executeSetp(component)
                    }
                }
            }
        } 

      stage("Migrate Tools") {
          steps {
              script {
                  toolComponentList.each { component ->
                    def componentName = component['name']
                    def componentVer = component['version']
                    if(componentVer == "NoTagAvailable") {
                        return 0
                    }
                    cloneGitRepo(componentName,componentVer)
                    def repoTar =  "${componentName}-${componentVer}.tar"
                    repoList << repoTar
                    compressRepo(componentName,repoTar)   
                    removeGitRepo(componentName)                         
                    }            
                }
           }
       }

        stage("archiveArtifacts") {
            steps {
                script {
                    println "archiveArtifacts"
                }
            //archiveArtifacts artifacts: '**', fingerprint: true
            stash includes: '**', name: 'appBinaries'
            }
        }

        stage("Download Microservice Image") {
         agent {
             label 'sftp'
         }

         steps {
            script {
                    microserviceList.each {
                        imageName = it.replaceAll(":","-")
                        downloadImage(it)
                        tarImage(it,imageName)
                    }
                }
            }
        }  

        stage("Migrate to SFTP") {
         agent {
             label 'sftp'
         }

         steps {
            script {
                    cleanWs()
                    //copyArtifacts filter: '**', fingerprintArtifacts: true, projectName: JOB_BASE_NAME, selector: specific(BUILD_NUMBER)
                    unstash 'appBinaries'

                    println repoList
                    sh "sudo mkdir -p /data/sftp/obphoenix/OpenBankingMigration/$tagName/REPO"

                    repoList.each {
                        migrateToSFTP("REPO",it)
                    }

                    println cqlList
                    sh "sudo mkdir -p /data/sftp/obphoenix/OpenBankingMigration/$tagName/CQL"

                    cqlList.each {
                        migrateToSFTP("CQL",it)
                    }
                    
                    println microserviceList
                    sh "sudo mkdir -p /data/sftp/obphoenix/OpenBankingMigration/$tagName/MICROSERVICE"

                    microserviceList.each {
                        migrateToSFTP("MICROSERVICE","/tmp/"+it.replaceAll(":","-")+".tar")
                    }
                    
                    println apiList
                    sh "sudo mkdir -p /data/sftp/obphoenix/OpenBankingMigration/$tagName/API"

                    apiList.each {
                        migrateToSFTP("API",it)
                    }
                    
                    println mConfigList
                    sh "sudo mkdir -p /data/sftp/obphoenix/OpenBankingMigration/$tagName/CONFIG"

                    mConfigList.each {
                        migrateToSFTP("CONFIG",it)
                    }
                    
                    println mStubList
                    sh "sudo mkdir -p /data/sftp/obphoenix/OpenBankingMigration/$tagName/STUB"
                    mStubList.each {
                        migrateToSFTP("STUB",it)
                    }
                    
                    sh "sudo find /data/sftp/obphoenix/OpenBankingMigration/$tagName/ -type f -print > $WORKSPACE/PRE-${tagName}.txt;"
                    def comList = readFile "$WORKSPACE/PRE-${tagName}.txt"
                    writeFile file: "$WORKSPACE/POST-${tagName}.txt", text: comList.replaceAll("/data/sftp/obphoenix/OpenBankingMigration/",""), encoding: "UTF-8"
                    def tagList = readFile file:"$WORKSPACE/POST-${tagName}.txt"
                    println tagList
                    def tagFileName = "$WORKSPACE/${tagName}-MD5.txt"
                    tagList.split("\n").each {
                        println it
                        tagMD5 = sh(returnStdout: true, script: "md5sum /data/sftp/obphoenix/OpenBankingMigration/$it")
                        tagMD5Array = tagMD5.split(" ")
                        tagMD5Value = tagMD5Array[0]       
                        sh "echo $it $tagMD5Value >> $tagFileName"
                    }
                    sh "sudo mv $WORKSPACE/${tagName}-MD5.txt /data/sftp/obphoenix/OpenBankingMigration/$tagName/"
                    sh "sudo mv $WORKSPACE/POST-${tagName}.txt /data/sftp/obphoenix/OpenBankingMigration/$tagName/${tagName}.txt"
                    writeFile file: "ToBeMigrated.txt", text: tagName, encoding: "UTF-8"
                    sh "sudo mv $WORKSPACE/ToBeMigrated.txt /data/sftp/obphoenix/OpenBankingMigration/ToBeMigrated.txt"
                }
            }
        } 

    }
}   