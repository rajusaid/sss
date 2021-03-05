def baseline

pipeline {
    agent  {
      label "DEVTEST"
    }
        parameters {
        string(defaultValue: "", description: 'What\'s Drop Name?', name: 'DROP_NAME')
    }
	
    environment {
        DEV_NEXUS_REGISTRY_CREDENTIAL = credentials('DEV_NEXUS_REGISTRY_CREDENTIAL')
        NEXUS_CREDENTIAL = credentials('NEXUS_CREDENTIAL')
        NEXUS_REGISTRY_CREDENTIAL = credentials('NEXUS_REGISTRY_CREDENTIAL')
    }
   
    stages {
        stage('Upload a file') {
            steps {
                script {
                    cleanWs()
					sh "curl -k -v -O -u $DEV_NEXUS_REGISTRY_CREDENTIAL https://devops-dev.nbsdev.co.uk/nexus/repository/OpenBankingMigration/$DROP_NAME/${DROP_NAME}.txt"
                }
            }
        }
        stage('Processing a file') {
            steps {
                script {
                    baseline= readFile "${DROP_NAME}.txt"
                }
            }
        }       
        stage('Migrating from Dev') {
            steps {
                script {
                    baselineList = baseline.split('\n')
                    baselineList.each {
                      line= it.split('/')
                      if(line[1] == "MICROSERVICE") {
                        def tagNameWithHy= line[2].substring(line[2].lastIndexOf('-'))
                        def tagName= tagNameWithHy.replaceAll("-","").replaceAll(".tar","")
                        def microserviceName= line[2].substring(0,line[2].lastIndexOf('-'))
                            withDockerServer([uri: DOCKER_DAEMON_SERVER_URL]) { 
                                withDockerRegistry([credentialsId: 'DEV_NEXUS_REGISTRY_CREDENTIAL', url: 'http://devops-dev.nbsdev.co.uk']) { 
                                  sh "docker pull devops-dev.nbsdev.co.uk/openbanking/$microserviceName:$tagName"
                            }
                          }    
                            withDockerServer([uri: DOCKER_DAEMON_SERVER_URL]) { 
                                withDockerRegistry([credentialsId: 'NEXUS_REGISTRY_CREDENTIAL', url: 'http://'+NEXUS_REGISTRY_URL]) { 
								  sh "docker tag devops-dev.nbsdev.co.uk/openbanking/$microserviceName:$tagName obdevopsngx.wip.nbsnet.co.uk/openbanking/$microserviceName:$tagName"
								  sh "docker push obdevopsngx.wip.nbsnet.co.uk/openbanking/$microserviceName:$tagName"
                            }
                          }						  
                      }
                      if(line[1] == "API") {
                        def tagNameWithHy= line[2].substring(line[2].lastIndexOf('-'))
                        def tagName= tagNameWithHy.replaceAll("-","")
                        def apiName= line[2].substring(0,line[2].lastIndexOf('-'))
						sh "curl -k -v -O -u $DEV_NEXUS_REGISTRY_CREDENTIAL https://devops-dev.nbsdev.co.uk/nexus/repository/OpenBankingMigration/$it"
						def fileName= line[2]
						sh "mv $fileName ${tagName}"
						sh "curl -k -v -u '$NEXUS_CREDENTIAL' https://obdevopsngx.wip.nbsnet.co.uk/nexus/repository/apigee-proxy/$apiName/ --upload-file $WORKSPACE/${tagName}"
					  }
                      if(line[1] == "CQL") {
						sh "curl -k -v -O -u $DEV_NEXUS_REGISTRY_CREDENTIAL https://devops-dev.nbsdev.co.uk/nexus/repository/OpenBankingMigration/$it"
						def cqlName= line[2]
						sh "curl -k -v -u '$NEXUS_CREDENTIAL' https://obdevopsngx.wip.nbsnet.co.uk/nexus/repository/OpenBankingMigration/$DROP_NAME/ --upload-file $WORKSPACE/${cqlName}"
					  }		
					  if(line[1] == "REPO") {
						repoName = line[2].replaceAll(".tar","")
						sh "curl -k -v -O -u $DEV_NEXUS_REGISTRY_CREDENTIAL https://devops-dev.nbsdev.co.uk/nexus/repository/OpenBankingMigration/$it"
						dir("$repoName") {
    						sh "tar -xf $WORKSPACE/${repoName}.tar"
							sh "git config  user.email 'ob-devops-noreply@nationwide.co.uk'"
							sh "git config  user.name 'DevOps'"
							sh "git push --tags --force"
							deleteDir()
						}
					  }	
                    }
                }
            }
        } 
        stage('Validation') {
            steps {
                script {
                    if(!DROP_NAME.toLowerCase().startsWith("mm"))
					build(job: "ValidateBaseline",parameters: [[$class: 'StringParameterValue', name: 'CHANGE_ID', value: ""],[$class: 'StringParameterValue', name: 'GIT_TAG', value: DROP_NAME]],propagate: true,quietPeriod: 5)
                }
            }
        }        
    }
}