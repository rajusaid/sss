/**
 * author: a.g.subramanian
 * invoke the baseline create the release notes
 */
import java.text.SimpleDateFormat

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

def r2RubyList
def r3TopazList
def r4OnyxList
def r41JadeList
def commonList
def toolList
def r2RubyComponentList
def r3TopazComponentList
def r4OnyxComponentList
def r41JadeComponentList
def commonComponentList
def toolComponentList
def tool
def currentDate 

pipeline {

  agent {
    label "any"
   }

    parameters {
      string(name: 'GIT_REF', defaultValue: '', description: 'git reference like commit id ,branch, tag')
    }

    stages {
  
      stage('Git-Checkout') {
        steps {
          script {
            if(GIT_REF != null || GIT_REF != "") {
                tagName = GIT_REF
            }
            
            if(env.GERRIT_REFNAME != null && env.GERRIT_REFNAME != "") {
                tagName = env.GERRIT_REFNAME.replaceAll("refs/tags/","")
            }
    
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
                r2RubyComponentList = r2RubyList

                _r3TopazFile = readFile file:"$WORKSPACE/OBIE2.0-R3-Topaz.txt"
                r3TopazList = convertTextToYaml(_r3TopazFile)
                r3TopazComponentList = r3TopazList

                _r41JadeFile = readFile file:"$WORKSPACE/OBIE3.1-R4-Jade.txt"
                r41JadeList = convertTextToYaml(_r41JadeFile)
                r41JadeComponentList = r41JadeList

                _commonFile = readFile file:"$WORKSPACE/OBIECommon.txt"
                commonList = convertTextToYaml(_commonFile)
                commonComponentList = commonList

                _toolFile = readFile file:"$WORKSPACE/OB-Tools.txt"
                toolList = convertTextToYaml(_toolFile)
                toolComponentList = toolList 

                def dateFormat = new SimpleDateFormat("dd-MM-yyyy")
                def date = new Date()

                currentDate = dateFormat.format(date)

            }
         }
      }

          stage('R2 Baseline Execution') {
            steps {
                script {    
                    println r2RubyComponentList
                    def r2TagsList = ""
                    def r2TagsListAffected = ""
                    r2RubyComponentList.COMPONENTS.each { component ->
                        if(component == r2RubyComponentList.COMPONENTS.last()) {
                            r2TagsList+=component['name']+" "+component['version']+""
                        } else {
                            r2TagsList+=component['name']+" "+component['version']+"<br> "
                        }  
                    }
                    r2RubyComponentList.AFFECTED_COMPONENTS.each { component ->
                        if(component == r2RubyComponentList.AFFECTED_COMPONENTS.last()) {
                            r2TagsListAffected+=component['name']+" "+component['version']+""
                        } else {
                            r2TagsListAffected+=component['name']+" "+component['version']+"<br> "
                        }  
                    }
                    def r2BaselineName= "OpenBanking-R2-$tagName-$currentDate-$BUILD_NUMBER"

                    println "BASELINE NAME: " + r2BaselineName
                    println "FULL: " + r2TagsList
                    println "AFFECTED: "+ r2TagsListAffected

                    if(r2TagsListAffected != "") {
                        def r2BuildN = build(job: "Manual_Release_Baseline",parameters: [[$class: 'StringParameterValue', name: 'BRANCH_NAME', value: "ruby"],[$class: 'StringParameterValue', name: 'COMPONENT_VERSIONS', value: r2TagsListAffected],[$class: 'StringParameterValue', name: 'TAGS', value: r2TagsList],[$class: 'StringParameterValue', name: 'EXISTING_BASELINE', value: "$r2BaselineName"],[$class: 'StringParameterValue', name: 'NEW_BASELINE', value: "$r2BaselineName"],[$class: 'StringParameterValue', name: 'REVIEWED', value: "YES"]],propagate: false,quietPeriod: 5)
                        dir(r2BaselineName) {
                            copyArtifacts filter: 'output/**', fingerprintArtifacts: true, projectName: "Manual_Release_Baseline", selector: specific(r2BuildN.number.toString())
                        }
                    }
                }
            }
          }

          stage('R3 Baseline Execution') {
            steps {
                script {      
                  println r3TopazComponentList
                  def r3TagsList =""
                  def r3TagsListAffected = ""
                  r3TopazComponentList.COMPONENTS.each { component ->
                        if(component == r3TopazComponentList.COMPONENTS.last()) {
                            r3TagsList+=component['name']+" "+component['version']+""
                        } else {
                            r3TagsList+=component['name']+" "+component['version']+"<br> "
                        }   
                  }
                  r3TopazComponentList.AFFECTED_COMPONENTS.each { component ->
                        if(component == r3TopazComponentList.AFFECTED_COMPONENTS.last()) {
                            r3TagsListAffected+=component['name']+" "+component['version']+""
                        } else {
                            r3TagsListAffected+=component['name']+" "+component['version']+"<br> "
                        }                   
                  }

                    def r3BaselineName= "OpenBanking-R3-$tagName-$currentDate-$BUILD_NUMBER"

                    println "BASELINE NAME: " + r3BaselineName
                    println "FULL: " + r3TagsList
                    println "AFFECTED: "+ r3TagsListAffected

                    if(r3TagsListAffected != "") {
                        def r3BuildN = build(job: "Manual_Release_Baseline",parameters: [[$class: 'StringParameterValue', name: 'BRANCH_NAME', value: "topaz"],[$class: 'StringParameterValue', name: 'COMPONENT_VERSIONS', value: r3TagsListAffected],[$class: 'StringParameterValue', name: 'TAGS', value: r3TagsList],[$class: 'StringParameterValue', name: 'EXISTING_BASELINE', value: "$r3BaselineName"],[$class: 'StringParameterValue', name: 'NEW_BASELINE', value: "$r3BaselineName"],[$class: 'StringParameterValue', name: 'REVIEWED', value: "YES"]],propagate: false,quietPeriod: 5)
                        dir(r3BaselineName) {
                            copyArtifacts filter: 'output/**', fingerprintArtifacts: true, projectName: "Manual_Release_Baseline", selector: specific(r3BuildN.number.toString())
                        }
                    }
                }
            }
          }

          stage('R4_1 Baseline Execution') {
            steps {
                script {      
                   println r41JadeComponentList
                  def r41TagsListAffected = ""
                  def r41TagsList = ""
                  r41JadeComponentList.COMPONENTS.each { component ->
                        if(component == r41JadeComponentList.COMPONENTS.last()) {
                            r41TagsList+=component['name']+" "+component['version']+""
                        } else {
                            r41TagsList+=component['name']+" "+component['version']+"<br> "
                        }
                  }
                  r41JadeComponentList.AFFECTED_COMPONENTS.each { component ->
                        if(component == r41JadeComponentList.AFFECTED_COMPONENTS.last()) {
                            r41TagsListAffected+=component['name']+" "+component['version']+""
                        } else {
                            r41TagsListAffected+=component['name']+" "+component['version']+"<br> "
                        }
                  }
                    def r41BaselineName= "OpenBanking-R4_1-$tagName-$currentDate-$BUILD_NUMBER"

                    println "BASELINE NAME: " + r41BaselineName
                    println "FULL: " + r41TagsList
                    println "AFFECTED: "+ r41TagsListAffected

                    if(r41TagsListAffected != "") {
                        def r41BuildN = build(job: "Manual_Release_Baseline",parameters: [[$class: 'StringParameterValue', name: 'BRANCH_NAME', value: "jade"],[$class: 'StringParameterValue', name: 'COMPONENT_VERSIONS', value: r41TagsListAffected],[$class: 'StringParameterValue', name: 'TAGS', value: r41TagsList],[$class: 'StringParameterValue', name: 'EXISTING_BASELINE', value: "$r41BaselineName"],[$class: 'StringParameterValue', name: 'NEW_BASELINE', value: "$r41BaselineName"],[$class: 'StringParameterValue', name: 'REVIEWED', value: "YES"]],propagate: false,quietPeriod: 5)
                        dir(r41BaselineName) {
                            copyArtifacts filter: 'output/**', fingerprintArtifacts: true, projectName: "Manual_Release_Baseline", selector: specific(r41BuildN.number.toString())
                        }                        
                    }
                }
            }
          }    

          stage('Common Baseline Execution') {
            steps {
                script {      
                    println commonComponentList
                  def cTagsListAffected = ""
                  def cTagsList = ""
                  commonComponentList.COMPONENTS.each { component ->
                        if(component == commonComponentList.COMPONENTS.last()) {
                            cTagsList+=component['name']+" "+component['version']+""
                        } else {
                            cTagsList+=component['name']+" "+component['version']+"<br> "
                        }
                  }
                  commonComponentList.AFFECTED_COMPONENTS.each { component ->
                        if(component == commonComponentList.AFFECTED_COMPONENTS.last()) {
                            cTagsListAffected+=component['name']+" "+component['version']+""
                        } else {
                            cTagsListAffected+=component['name']+" "+component['version']+"<br> "
                        }
                  }
                    def rCBaselineName= "OpenBanking-Versionless-$tagName-$currentDate-$BUILD_NUMBER"

                    println "BASELINE NAME: " + rCBaselineName
                    println "FULL: " + cTagsList
                    println "AFFECTED: "+ cTagsListAffected

                    if(cTagsListAffected != "") {
                        def rCBuildN = build(job: "Manual_Release_Baseline",parameters: [[$class: 'StringParameterValue', name: 'BRANCH_NAME', value: "dev"],[$class: 'StringParameterValue', name: 'COMPONENT_VERSIONS', value: cTagsListAffected],[$class: 'StringParameterValue', name: 'TAGS', value: cTagsList],[$class: 'StringParameterValue', name: 'EXISTING_BASELINE', value: "$rCBaselineName"],[$class: 'StringParameterValue', name: 'NEW_BASELINE', value: "$rCBaselineName"],[$class: 'StringParameterValue', name: 'REVIEWED', value: "YES"]],propagate: false,quietPeriod: 5)
                        dir(rCBaselineName) {
                            copyArtifacts filter: 'output/**', fingerprintArtifacts: true, projectName: "Manual_Release_Baseline", selector: specific(rCBuildN.number.toString())
                        }                           
                    }
                 }
               }
            }
          stage('Tool Execution') {
            steps {
                script {      
                  println toolComponentList
                  def toolTagsListAffected = ""
                  def toolTagsList = ""
                  toolComponentList.COMPONENTS.each { component ->
                        if(component == toolComponentList.COMPONENTS.last()) {
                            toolTagsList+=component['name']+" "+component['version']+""
                        } else {
                            toolTagsList+=component['name']+" "+component['version']+"<br> "
                        }
                  }
                  toolComponentList.AFFECTED_COMPONENTS.each { component ->
                        if(component == toolComponentList.AFFECTED_COMPONENTS.last()) {
                            toolTagsListAffected+=component['name']+" "+component['version']+""
                        } else {
                            toolTagsListAffected+=component['name']+" "+component['version']+"<br> "
                        }
                  }
                    def toolBaselineName= "OpenBanking-Tools-$tagName-$currentDate-$BUILD_NUMBER"

                    println "BASELINE NAME: " + toolBaselineName
                    println "FULL: " + toolTagsList
                    println "AFFECTED: "+ toolTagsListAffected

                    if(toolTagsListAffected != "") {
                        def toolCBuildN = build(job: "Manual_Release_Baseline",parameters: [[$class: 'StringParameterValue', name: 'BRANCH_NAME', value: "dev"],[$class: 'StringParameterValue', name: 'COMPONENT_VERSIONS', value: toolTagsListAffected],[$class: 'StringParameterValue', name: 'TAGS', value: toolTagsList],[$class: 'StringParameterValue', name: 'EXISTING_BASELINE', value: "$toolBaselineName"],[$class: 'StringParameterValue', name: 'NEW_BASELINE', value: "$toolBaselineName"],[$class: 'StringParameterValue', name: 'REVIEWED', value: "YES"]],propagate: false,quietPeriod: 5)
                        dir(toolBaselineName) {
                            copyArtifacts filter: 'output/**', fingerprintArtifacts: true, projectName: "Manual_Release_Baseline", selector: specific(toolCBuildN.number.toString())
                        }                           
                    }
                 }
               }
            }            
    }
    post {
        always {
            archiveArtifacts artifacts: '**/output/*', fingerprint: true
        }
    }    
}