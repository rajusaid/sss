/**
 * author: a.g.subramanian
 * Migrate the pipeline to SFTP
 */

pipeline {
    agent any
    stages {
        stage("git checkout") {
            steps {
                script {
                    cleanWs()
                    checkout([$class: 'GitSCM',branches: [[name: "dev"]],doGenerateSubmoduleConfigurations: false,
                    extensions: [],
                    gitTool: 'Default',
                    submoduleCfg: [],userRemoteConfigs: [[url: 'ssh://jenkins@gerrit:29418/ob-pipeline-script',credentialsId: 'adop-jenkins-master']]   ])
                }
            }
        }
        stage("Tar it") {
            steps {
                script {
                     today=new Date().format('ddMMyyyy')
                     sh "tar -cvf pipeline-script-${today}-${BUILD_NUMBER}.tar ."
                }
            archiveArtifacts artifacts: "pipeline-script-${today}-${BUILD_NUMBER}.tar", fingerprint: true
            }
        }        
        stage("To SFTP") {
         agent {
             label 'sftp'
         }
         steps {
                script {
                    copyArtifacts filter: '**', fingerprintArtifacts: true, projectName: JOB_BASE_NAME, selector: specific(BUILD_NUMBER)
                    sh "sudo cp pipeline-script-${today}-${BUILD_NUMBER}.tar /data/sftp/obphoenix/miscellaneous/"
                }
            }
        }           
    }
}