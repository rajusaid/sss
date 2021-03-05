/**
 * author: a.g.subramanian
 * Process the baseline and migrate to Sandbox
 */

def r2RubyList
def r3TopazList
def r4OnyxList
def r41JadeList
def commonList
def configList
def stubList
def r2RubyComponentList
def r3TopazComponentList
def r4OnyxComponentList
def r41JadeComponentList
def commonComponentList
def configComponentList
def stubComponentList
microserviceList = []
apiList = []
cqlList = []
mConfigList = []
mStubList = []
repoList = []
sourceCodeList = []
tagName = ""

def executeSetp(releaseName,component,zipRepo=false) {
    def componentName = component['name']
    def componentVer = component['version']
    def componentVerWithoutPrefix = componentVer.replaceAll("[A-Za-z]","")
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
            compressRepo(componentName, componentName+".tar", ".")
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
    extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: componentName]],
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
    httpRequest authentication: 'NEXUS_CREDENTIAL', ignoreSslErrors: true, responseHandle: 'NONE', url: env.APIGEE_PROXY_NEXUS_URL+"/$componentName/${componentVer}.zip", validResponseCodes: '200', outputFile: "$componentName-${componentVer}.zip"
    apiList << "${releaseName}~${componentName}-${componentVer}.zip"
}

def downloadStub(componentName,componentVer) {
    httpRequest authentication: 'NEXUS_CREDENTIAL', ignoreSslErrors: true, responseHandle: 'NONE', url: "https://devops.obphoenix.co.uk/nexus/repository/tomcat-stubs/$componentVer/${componentName}.war", validResponseCodes: '200', outputFile: "${componentName}.war"
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
    sh "touch ob-us-actuator-4_3.0.1.tar"  // Temporary fix to missing actuator file
    sh "aws s3 cp $path s3://obtpp-artifacts-eu-west-2/OpenBanking/$tagName/$type/ --acl bucket-owner-full-control --sse aws:kms --sse-kms-key-id arn:aws:kms:eu-west-2:454579647685:key/22e2ebf2-a6b0-44b4-a0a4-558afded2254"
    //sh "aws s3 cp $path s3://sandbox-test-bucket-eu-west-1/OpenBanking/$tagName/$type/ --profile comply"
}

def runHelmTransformation(scriptFilePath, baselineFilePath) {
    sshagent (credentials: ['adop-jenkins-master']) {
        sh "python ${scriptFilePath}/helmTransformation.py --baseline '${baselineFilePath}'"
    }
}

def compressHelmCharts() {
    sh '''
        OUTPUT=$(ls ob-helm-transformation-scripts/output/)
        for RELEASE in $OUTPUT
        do
            COMPONENTS=$(ls ob-helm-transformation-scripts/output/$RELEASE)
            for COMPONENT in $COMPONENTS
            do
                COMPONENT_TAG=$(echo ${COMPONENT##*-})
                VERSIONS=$(ls ob-helm-transformation-scripts/output/$RELEASE/$COMPONENT)
                for VERSION in $VERSIONS
                do
                    tar -cvf $VERSION-$COMPONENT_TAG.tar ob-helm-transformation-scripts/output/$RELEASE/$COMPONENT/$VERSION
                done
            done
        done
    '''
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
                    _r2RubyFile = readFile file:"$WORKSPACE/OBIE1.1-R2-Ruby.txt"
                    r2RubyList = convertTextToYaml(_r2RubyFile)
                    r2RubyComponentList = r2RubyList.COMPONENTS

                    _r3TopazFile = readFile file:"$WORKSPACE/OBIE2.0-R3-Topaz.txt"
                    r3TopazList = convertTextToYaml(_r3TopazFile)
                    r3TopazComponentList = r3TopazList.COMPONENTS
                    
                    _r4OnyxFile = readFile file:"$WORKSPACE/OBIE3.0-R4-Onyx.txt"
                    r4OnyxList = convertTextToYaml(_r4OnyxFile)
                    r4OnyxComponentList = r4OnyxList.COMPONENTS
                        
                    
                    _commonFile = readFile file:"$WORKSPACE/OBIECommon.txt"
                    commonList = convertTextToYaml(_commonFile)
                    commonComponentList = commonList.COMPONENTS

                    _configFile = readFile file:"$WORKSPACE/OBIE-Config.txt"
                    configList = convertTextToYaml(_configFile)
                    configComponentList = configList.COMPONENTS       

                    _stubFile = readFile file:"$WORKSPACE/OB-Stubs.txt"
                    stubList = convertTextToYaml(_stubFile)
                    stubComponentList = stubList.COMPONENTS     
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
        stage("Migrate R2") {
            steps {
                script {
                    r2RubyComponentList.each { component ->
                      //  executeSetp("OpenBankingR2",component)
                    }
                }
            }
        }
        stage("Migrate R3") {
            steps {
                script {
                    r3TopazComponentList.each { component ->
                       // executeSetp("OpenBankingR3",component)
                    }
                }
            }
        }

        stage("Migrate R4_1") {
            steps {
                script {
                    r41JadeComponentList.each { component ->
                       executeSetp("OpenBankingR4_1",component)
                    }
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
        stage("Helm Transformation") {
            steps {
                script {
                    cloneGitRepo("ob-helm-transformation-scripts","develop")
                    sh "cp ${WORKSPACE}/OBIE2.0-R3-Topaz.txt ${WORKSPACE}/ob-helm-transformation-scripts/baseline/OBIE2.0-R3-Topaz.txt"
                    sh "cp ${WORKSPACE}/OBIE3.0-R4-Onyx.txt ${WORKSPACE}/ob-helm-transformation-scripts/baseline/OBIE3.0-R4-Onyx.txt"
                    sh "cp ${WORKSPACE}/OBIE3.1-R4-Jade.txt ${WORKSPACE}/ob-helm-transformation-scripts/baseline/OBIE3.1-R4-Jade.txt"
                    sh "cp ${WORKSPACE}/OBIE1.1-R2-Ruby.txt ${WORKSPACE}/ob-helm-transformation-scripts/baseline/OBIE1.1-R2-Ruby.txt"
                    sh "cp ${WORKSPACE}/OBIECommon.txt ${WORKSPACE}/ob-helm-transformation-scripts/baseline/OBIECommon.txt"
                    runHelmTransformation("${WORKSPACE}/ob-helm-transformation-scripts", "baseline/OBIE2.0-R3-Topaz.txt")
                    runHelmTransformation("${WORKSPACE}/ob-helm-transformation-scripts", "baseline/OBIE3.0-R4-Onyx.txt")
                    runHelmTransformation("${WORKSPACE}/ob-helm-transformation-scripts", "baseline/OBIE3.1-R4-Jade.txt")
                    runHelmTransformation("${WORKSPACE}/ob-helm-transformation-scripts", "baseline/OBIE1.1-R2-Ruby.txt")
                    runHelmTransformation("${WORKSPACE}/ob-helm-transformation-scripts", "baseline/OBIECommon.txt")
                    compressHelmCharts()
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

        stage("Migrate STUB") {
            agent {
                label 's3testslave'
            }
            steps {
                script {
                    def stubComponentVer
                    stubComponentList.each { component ->
                        if(component['name'] == "ob-soapui-mocked-nem") {
                            stubComponentVer = component['version']
                        }
                    }
                    cloneGitRepo("ob-soapui-mocked-nem",stubComponentVer)
                    dir("ob-soapui-mocked-nem") {
                        def XMLFile = findFiles(glob: '*.xml')
                        stubComponentVer = stubComponentVer.replaceAll("[A-Za-z]", "")
                        XMLFile.each {
                            println it
                            fileName = it.toString().replaceAll(".xml","")
                            downloadStub(fileName,stubComponentVer)
                            migrateToS3("STUB", "${fileName}.war")
                        }
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
                        migrateToS3("HELM/${beforeImageName[0]}",beforeImageName[1].replaceAll(":","-")+".tar")
                        migrateToS3("MICROSERVICE/${beforeImageName[0]}","/tmp/"+beforeImageName[1].replaceAll(":","-")+".tar")
                    }
                    
                    println apiList
                    apiList.each {
                        apiZipName = it.split("~")
                        migrateToS3("API/${apiZipName[0]}",apiZipName[1])
                    }
                    
                    println mConfigList
                    mConfigList.each {
                        migrateToS3("CONFIG",it)
                    }
                
                    print sourceCodeList
                    sourceCodeList.each {
                        sourceCodeName = it.split("~")
                        migrateToS3("SOURCE_CODE/MICROSERVICE",sourceCodeName[1])
                    }
                    
                }
            }
        } 
    }
}