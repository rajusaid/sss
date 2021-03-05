/**
 * author: a.g.subramanian
 * Process the baseline and migrate to SFTP
 */

componentList = []
microserviceList = []
apiList = []
cqlList = []
mConfigList = []
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

def convertTextToList(baselineFile) {
  baselineFile.split("\n").each {
    def componentMap = [:]
    def componentInArray = it.split(" ")
    if(componentInArray[0].matches("ob-(.*)")) {
        componentMap.put("name",componentInArray[0].trim())
        componentMap.put("version",componentInArray[1].trim())
        componentList.add(componentMap)      
     }
  }
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
        
        stage("Upload Baseline File") {
            agent {
                label 'any'
            }
            steps {
                script {
                    def userInput = input message: 'Upload file', parameters: [file(name: 'BASELINE_FILE',description: 'Baseline File'),string(name: 'NICK_NAME_OF_DROP',default: "MM_",description: "Nick name of the DROP. it must be start with 'MM_' - Manual Migration")]
                    envPrefix = "$userInput.ENV_PREFIX"
                    new hudson.FilePath(new File("$workspace/baseline.txt")).copyFrom(userInput.BASELINE_FILE)
                    stash includes: '**', name: 'builtSources'
                    userInput.BASELINE_FILE.delete()
                    tagName = userInput.NICK_NAME_OF_DROP.trim()
                }
            }
        }
      
        stage('Reading baseline file') {
          steps {
            script {
                unstash 'builtSources'
                _baseline = readFile file:"$WORKSPACE/baseline.txt"    
                convertTextToList(_baseline)
            }
            archiveArtifacts artifacts: '*.txt', fingerprint: true
         }
      }

      stage("Migrate Components") {
          steps {
              script {
                    componentList.each { component ->
                       executeSetp(component)
                     }
                 }
             }
         }

        stage("archiveArtifacts") {
            steps {
                script {
                    println "archiveArtifacts"
                    //archiveArtifacts artifacts: '**', fingerprint: true
                    stash includes: '**', name: 'appBinaries'
                }
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
                }
            }
        } 
    }
}   