/**
 * author: a.g.subramanian
 * Process the baseline and migrate to Artifactory
 */

def r2RubyList
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
    println type
    if(type == "MICROSERVICE") {
        compressCQLZip(componentName,componentVerWithoutPrefix)
        getMicroserviceImage(componentName,componentVerWithoutPrefix)
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
    
	parameters {		
		string (
			name: 'BASELINE_TAG',
			defaultValue: '',
			description: 'Baseline Tag')
	}

    stages {
        
      stage('Git-Checkout') {
        steps {
          script {
            cleanWs()
            tagName = BASELINE_TAG
            checkout([$class: 'GitSCM', branches: [[name: "${tagName}"]], doGenerateSubmoduleConfigurations: false, 
            extensions: [], 
            submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'jenkins-github-credentials', url: "$BASE_GIT_URL/ob-baseline-release"]]])
          }
        }
      }
      
      stage('Reading baseline file') {
        steps {
            script {
              _r2RubyFile = readFile file:"$WORKSPACE/OBIE1.1-R2-Ruby.txt"
              r2RubyList = convertTextToYaml(_r2RubyFile)
              r2RubyComponentList = r2RubyList.AFFECTED_COMPONENTS
	      println "Affected r2RubyComponentList: " + r2RubyComponentList

              _r41JadeFile = readFile file:"$WORKSPACE/OBIE3.1-R4-Jade.txt"
              r41JadeList = convertTextToYaml(_r41JadeFile)
              r41JadeComponentList = r41JadeList.AFFECTED_COMPONENTS
	      println "Affected r41JadeComponentList: " + r41JadeComponentList
              
              _commonFile = readFile file:"$WORKSPACE/OBIECommon.txt"
              commonList = convertTextToYaml(_commonFile)
              commonComponentList = commonList.AFFECTED_COMPONENTS
	      println "Affected commonComponentList: " + commonComponentList

              _configFile = readFile file:"$WORKSPACE/OBIE-Config.txt"
              configList = convertTextToYaml(_configFile)
              configComponentList = configList.AFFECTED_COMPONENTS
	      println "Affected configComponentList: " + configComponentList

              _stubFile = readFile file:"$WORKSPACE/OB-Stubs.txt"
              stubList = convertTextToYaml(_stubFile)
              stubComponentList = stubList.AFFECTED_COMPONENTS
	      println "Affected stubComponentList: " + stubComponentList

               _toolFile = readFile file:"$WORKSPACE/OB-Tools.txt"
              toolList = convertTextToYaml(_toolFile)
              toolComponentList = toolList.AFFECTED_COMPONENTS
	      println "Affected toolComponentList: " + toolComponentList
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


        stage("Migrate to Artifactory") {
         steps {
            script {
                    println repoList

                    repoList.each {
                        migrateToArtifactory("REPO",it)
                    }

                    println cqlList
                    cqlList.each {
                        migrateToArtifactory("CQL",it)
                    }
                    
                    println mConfigList
                    mConfigList.each {
                        migrateToArtifactory("CONFIG",it)
                    }
                    
                    println mStubList
                    mStubList.each {
                        migrateToArtifactory("STUB",it)
                    }
                    
                    println apiList
                    println microserviceList
                    
                    StringBuilder sb = new StringBuilder();

                    apiList.each {
                        sb.append("API/"+it+"\n")
                    }
                    microserviceList.each {
                        sb.append("MICROSERVICE/"+it+"\n")
                    }    
                    
                    writeFile file: "$WORKSPACE/${tagName}.txt", text: sb.toString()

                    sh "curl -k -u ${ARTIFACTORY_USERNAME}:${ARTIFACTORY_PASSWORD} ${ARTIFACTORY_URL}/OpenBankingMigration/$tagName/${tagName}.txt --upload-file $WORKSPACE/${tagName}.txt"

                }
            }
        }

        stage('Migrate drop to Sandbox S3') {
            steps {
                script {
                    if(!BASELINE_TAG.toLowerCase().startsWith("mm"))
					build(job: "sandbox-baseline-migration",parameters: [[$class: 'StringParameterValue', name: 'GIT_REFNAME', value: BASELINE_TAG ]],propagate: true,quietPeriod: 5)
                }
            }
        }

    }
}   
