/**
 * author: a.g.subramanian
 * Process the baseline and migrate to Sandbox
 */

def r41JadeList
def commonList
def configList
def stubList
def r41JadeComponentList
def commonComponentList
def configComponentList
microserviceList = []
apiList = []
cqlList = []
mConfigList = []
repoList = []
tagName = ""
microserviceMap = [:]
apigeeProxyMap = [:]
microserviceRoute = [:]
ignoreComponentList = ["ob-bat-strandedpayments","ob-us-audit-event","ob-us-actuator","ob-api-audit-event","ob-api-cryptoservice"]

def executeSetp(releaseName,component,zipRepo=false) {
    def componentName = component['name']
    def componentVer = component['version']
    def componentVerWithoutPrefix = componentVer.replaceAll("[A-Za-z]","")
    cloneGitRepo(componentName,componentVer)
    if(zipRepo){
         zip zipFile: "${componentName}.zip", archive: false, dir: "$WORKSPACE/$componentName"
         mConfigList << "${componentName}.zip"
    } else {
        def type = findComponentType(componentName)
        if(!ignoreComponentList.contains(componentName)) {
            if(type == "API") {
                def apiType = getAPIType(componentName)
                def apiMap = [
                    "name" : getAPIName(componentName),
                    "version" : componentVerWithoutPrefix,
                    "type": apiType,
                    "internalProxy": apiType == "apiproxy" ? isInternalProxy(componentName) : false
                ]
                apigeeProxyMap.put(componentName,apiMap)
                downloadAPIZip(releaseName,componentName,componentVerWithoutPrefix)
            } else if(type == "MICROSERVICE") {
                def microserviceName = getMicroserviceName(componentName)
                def uServiceMap = [
                    "name" : microserviceName,
                    "version" : componentVerWithoutPrefix,
                    "type": componentName == "ob-us-crypto-service" ? "CRYPTO" : cqlExist(componentName) ? "SNE" : "DNE",
                    "route": microserviceRoute.get(microserviceName)
                ]      
                microserviceMap.put(componentName,uServiceMap)
                compressCQLZip(releaseName,componentName,componentVerWithoutPrefix)
                compressRepo(componentName, componentName+".tar", ".")
                getMicroserviceImage(releaseName,componentName,componentVerWithoutPrefix)
            }
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
    extensions: [[$class: 'CloneOption', timeout: 10, noTags: false, shallow: true],[$class: 'RelativeTargetDirectory', relativeTargetDir: componentName],[$class: 'CloneOption', timeout: 10, noTags: false, shallow: true]],
    gitTool: 'Default',
    submoduleCfg: [],userRemoteConfigs: [[url:  "$BASE_GIT_URL/" +componentName,credentialsId: 'jenkins-github-credentials']]   ])
}

def compressCQLZip(releaseName,componentName,componentVer) {
    cqlFolder = fileExists file: "$WORKSPACE/$componentName/src/main/resources/dbscripts"
    if(cqlFolder) {
         zip zipFile: "${componentName}-${componentVer}.zip", archive: false, dir: "$WORKSPACE/$componentName/src/main/resources/dbscripts"
         cqlList << "${releaseName}~${componentName}-${componentVer}.zip"
    }
}

def compressRepo(componentName,tarName,tarPath) {
    dir("$WORKSPACE/$componentName") {
        sh "tar -cvf $tarName $tarPath"
        sh "mv $tarName $WORKSPACE/"
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
    httpRequest authentication: 'ARTIFACTORY_CI_CREDENTIALS', ignoreSslErrors: true, responseHandle: 'NONE', url: env.APIGEE_PROXY_ARTIFACTORY_URL+"/$componentName/${componentVer}.zip", validResponseCodes: '200', outputFile: "$componentName-${componentVer}.zip"
    apiList << "${releaseName}~${componentName}-${componentVer}.zip"
}

def downloadImage(componentNameWithVer,imageName) {
    withDockerRegistry([credentialsId: 'ARTIFACTORY_CI_CREDENTIALS', url: 'http://'+"$ARTIFACTORY_REL_DOCKER_REGISTORY"]) { 
        docker.image("$ARTIFACTORY_REL_DOCKER_REGISTORY/openbanking/$componentNameWithVer").pull()
    } 
}

def tarImage(componentNameWithVer,imageName) {
    sh "docker save $ARTIFACTORY_REL_DOCKER_REGISTORY/openbanking/$componentNameWithVer > /sandbox-images/${imageName}.tar"        
}

def migrateToS3(type,path) {
    sh "aws s3 cp $path s3://obtpp-artifacts-eu-west-2/OpenBanking/ePaaS/$tagName/$type/ --sse aws:kms --sse-kms-key-id arn:aws:kms:eu-west-2:454579647685:key/22e2ebf2-a6b0-44b4-a0a4-558afded2254"
}

def getMicroserviceName(componentName) {
    appFile = fileExists file: "$WORKSPACE/${componentName}/src/main/resources/application.properties"
    if(appFile) {
        def props = readProperties  file: "$WORKSPACE/$componentName/src/main/resources/application.properties"
        return props['spring.application.name']
    }    
}

def cqlExist(componentName) {
  isExist = fileExists file: "$WORKSPACE/${componentName}/src/main/resources/dbscripts"
  return isExist
}

def getAPIName(componentName) {
  def mvnVersion = readMavenPom file: "${WORKSPACE}/${componentName}/pom.xml"
  return mvnVersion.getArtifactId()
}

def isInternalProxy(componentName) {
  def fileName = sh(script: "ls ${WORKSPACE}/${componentName}/apiproxy/proxies/", returnStdout: true).split("\n")
  def fileInstance 
  def internalProxy = "False"
  fileName.each{ item -> 
    fileInstance = readFile "${WORKSPACE}/${componentName}/apiproxy/proxies/${item}"
    lines = fileInstance.readLines()
    def result = lines.findAll { it.contains('<VirtualHost>') }
    if(result.size() <= 0) {
      internalProxy =  "True"
    }
  }
  return internalProxy
}

def getAPIType(componentName) {
  isAPIExist = fileExists file: "$WORKSPACE/${componentName}/apiproxy"
  isSharedFlowExist = fileExists file: "$WORKSPACE/${componentName}/sharedflowbundle"
  def type = "apiproxy"
    if(isAPIExist) {
        type = "apiproxy"
    } else if(isSharedFlowExist) {
        type = "sharedflowbundle"
    }  
  return type
}

pipeline {
    agent {
        label "ndap-s3"
    }

    parameters {      
        string (
            name: 'GIT_REFNAME',
            defaultValue: 'Q1Feb20_Drop8',
            description: 'git tag of ob-baselime-release to migrate')
    }

    stages {
        stage('Git-Checkout') {
            steps {
                script {
                    tagName = GIT_REFNAME.replaceAll("refs/tags/","")
                    cleanWs()

                    checkout([$class: 'GitSCM',branches: [[name: "$tagName"]],doGenerateSubmoduleConfigurations: false,
					extensions: [],
					gitTool: 'Default',
					submoduleCfg: [],userRemoteConfigs: [[url: '$BASE_GIT_URL/ob-baseline-release.git',credentialsId: 'jenkins-github-credentials']]   ])

                    checkout([$class: 'GitSCM',branches: [[name: 'master']],doGenerateSubmoduleConfigurations: false,
                    extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "ob-pipeline-script"]],
                    gitTool: 'Default',
                    submoduleCfg: [],userRemoteConfigs: [[url: "$BASE_GIT_URL/$PIPELINE_REPO",credentialsId: 'jenkins-github-credentials']]   ])                       

                }
            }
        }
        
        stage('Reading baseline file') {
            steps {
                script {
                    // _r3TopazFile = readFile file:"$WORKSPACE/OBIE2.0-R3-Topaz.txt"
                    // r3TopazList = convertTextToYaml(_r3TopazFile)
                    // r3TopazComponentList = r3TopazList.COMPONENTS
                    
                    _r41JadeFile = readFile file:"$WORKSPACE/OBIE3.1-R4-Jade.txt"
                    r41JadeList = convertTextToYaml(_r41JadeFile)
                    r41JadeComponentList = r41JadeList.COMPONENTS
                    
                    _commonFile = readFile file:"$WORKSPACE/OBIECommon.txt"
                    commonList = convertTextToYaml(_commonFile)
                    commonComponentList = commonList.COMPONENTS

                    _configFile = readFile file:"$WORKSPACE/OBIE-Config.txt"
                    configList = convertTextToYaml(_configFile)
                    configComponentList = configList.COMPONENTS       

                    microserviceRoute = readYaml file: "$WORKSPACE/ob-pipeline-script/resources/openshift-route.yaml"
                }
            }
        }
        stage("Migrate Baseline") {
            steps {
                script {
                    cloneGitRepo("ob-baseline-release",tagName)
                    repoTar =  "ob-baseline-release-"+tagName+".tar"
                    repoList << repoTar
                    compressRepo("ob-baseline-release",repoTar,".")   
                    removeGitRepo("ob-baseline-release")               
                }
            }
        }

        stage("Migrate Versionless") {
            steps {
                script {
                    commonComponentList.each { component ->
                       executeSetp("OpenBankingVersionless",component)
                    }
                }
            }
        }   

        stage("Migrate R4_1") {
            steps {
                script {
                    r41JadeComponentList.each { component ->
                       executeSetp("OpenBanking4_1",component)
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
                        executeSetp("",component,true)
                    }
                }
            }
        }

        stage("archiveArtifacts") {
            steps {
                script {
                    println "archiveArtifacts"
                }
                stash includes: '**', name: 'appBinaries'
            }
        }
        
        stage("Download Microservice Image") {
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
            steps {
                script {
                    cleanWs()
                    unstash 'appBinaries'

                    println repoList
                    repoList.each {
                        migrateToS3("REPO",it)
                    }

                    println cqlList
                    cqlList.each {
                        cqlName =  it.split("~")
                        migrateToS3("CQL",cqlName[1])
                    }
                    
                    println microserviceList
                    microserviceList.each {
                        beforeImageName = it.split("~")
                        migrateToS3("MICROSERVICE","/sandbox-images/"+beforeImageName[1].replaceAll(":","-")+".tar")
                    }
                    
                    println apiList
                    apiList.each {
                        apiZipName = it.split("~")
                        migrateToS3("API",apiZipName[1])
                    }
                    
                    println mConfigList
                    mConfigList.each {
                        migrateToS3("CONFIG",it)
                    }
                    def finalMap = [:]
                    finalMap.put("MICROSERVICE",microserviceMap)
                    finalMap.put("API",apigeeProxyMap)
                    writeYaml file: "baseline.yaml", data: finalMap  
                    sh "aws s3 cp baseline.yaml s3://obtpp-artifacts-eu-west-2/OpenBanking/ePaaS/$tagName/ --sse aws:kms --sse-kms-key-id arn:aws:kms:eu-west-2:454579647685:key/22e2ebf2-a6b0-44b4-a0a4-558afded2254"
                }
            }
        } 
    }
}