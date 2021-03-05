/**
 * author: bhavya.b.pandey
 * Process the baseline and migrate to Artifactory
 */

def repoMigrateList
apiRepoList = []
microserviceRepoList = []
microserviceList = []
apiList = []
cqlList = []
mConfigList = []
mStubList = []
repoList = []
tagName = ""

def executeSetp(component) {
    def microserviceName
    def componentName = component['name']
    def componentVer = component['version']
    def componentVerWithoutPrefix = componentVer.replaceAll("[A-Za-z]","")
    cloneGitRepo(componentName,componentVer)
    repoTar =  componentName+"-"+componentVerWithoutPrefix+".tar"
    repoList << repoTar
    compressRepo(componentName,repoTar)
    def type = findComponentType(componentName)
    println type
    if(type == "MICROSERVICE") {
        compressCQLZip(componentName,componentVerWithoutPrefix)
        microserviceName = getMicroserviceImage(componentName,componentVerWithoutPrefix)

    } else if(type == "API") {      
      apiList << "${componentName}:${componentVerWithoutPrefix}"
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

def getMicroserviceImage(componentName,componentVer) {
    appFile = fileExists file: "$WORKSPACE/$componentName/src/main/resources/application.properties"
    if(appFile) {
        def props = readProperties  file: "$WORKSPACE/$componentName/src/main/resources/application.properties"
        microserviceList << props['spring.application.name']+":"+componentVer
           return props['spring.application.name']
    }
}

def cloneGitRepo(componentName,componentVer) {
    println "Cloning: " + componentName
    checkout([$class: 'GitSCM',branches: [[name: componentVer ]],doGenerateSubmoduleConfigurations: false,
    extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: componentName]],
    gitTool: 'Default',
    submoduleCfg: [],userRemoteConfigs: [[url: "${BASE_GIT_URL}/"+componentName,credentialsId: 'jenkins-github-credentials']]   ])
}

def compressCQLZip(componentName,componentVer) {
    def isCQLScriptExist = fileExists file: "$WORKSPACE/${componentName}/src/main/resources/dbscripts"
    if(isCQLScriptExist) {
         zip zipFile: "$WORKSPACE/${componentName}-${componentVer}.zip", archive: false, dir: "$WORKSPACE/$componentName/src/main/resources/dbscripts"
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


def migrateToArtifactory(type,path) {
    sh "curl -k -u ${ARTIFACTORY_USERNAME}:${ARTIFACTORY_PASSWORD} ${ARTIFACTORY_URL}/OpenBankingMigration/$tagName/$type/ --upload-file $path"
}


pipeline {
    agent {
        label "any"
    }
    
    environment {
        ARTIFACTORY_CREDENTIALS = credentials('ARTIFACTORY_CI_CREDENTIALS')
        ARTIFACTORY_USERNAME = "${env.ARTIFACTORY_CREDENTIALS_USR}"
        ARTIFACTORY_PASSWORD = "${env.ARTIFACTORY_CREDENTIALS_PSW}"
    }
    parameters{
    string(name: 'DROP_NAME', defaultValue: 'releaseName', description: 'Repository Name')
     string(name: 'envPrefix', defaultValue: 'AS_DEV', description: 'Repository Name')
     text defaultValue: 'repo-name tag-name', description: 'enter repos to be migrated', name: 'baseline'
        
    }
    stages {
      
        stage("Get baseline file") {
          steps {
            script {
                    writeFile file: "$WORKSPACE/baseline.txt", text: baseline
                    println "file created."
                }
            }
        } 
        stage("Read Baseline File") {
            steps {
                script {
                    if(envPrefix == null || envPrefix == "") {
                        println "Parameters are missing"
                        sh "exit 1"
                    }
                    tagName = "$DROP_NAME"
                    _baseline = readFile file:"$WORKSPACE/baseline.txt"
                    archiveArtifacts artifacts: "baseline.txt", fingerprint: true
                    _components =convertTextToYaml(_baseline)
                    repoMigrateList = _components.COMPONENTS
                }
            }
        }
          
        stage("Migrate Components") {
          steps {
            script {
                    repoMigrateList.each { component ->
                        executeSetp(component)
                    }
                }
            }
        } 

        stage("Migrate to Artifactory") {
         steps {
            script {
                try{
                    println repoList

                    repoList.each {
                        migrateToArtifactory("REPO",it)
                    }
                 
                    StringBuilder sb = new StringBuilder();
                    apiList.each {
                        sb.append("API/"+it+"\n")
                    }
                    microserviceList.each {
                        sb.append("MICROSERVICE/"+it+"\n")
                    }    
                   
                    writeFile file: "$WORKSPACE/${DROP_NAME}.txt", text: sb.toString()
                    sh "curl -k -u ${ARTIFACTORY_USERNAME}:${ARTIFACTORY_PASSWORD} ${ARTIFACTORY_URL}/OpenBankingMigration/$tagName/${DROP_NAME}.txt --upload-file $WORKSPACE/${DROP_NAME}.txt"
                    } catch(e){
                        println e
                    }
                }
            }
        } 
    }

    
}   