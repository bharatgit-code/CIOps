import org.egov.jenkins.ConfigParser
import org.egov.jenkins.Utils
import org.egov.jenkins.models.JobConfig
import org.egov.jenkins.models.BuildConfig

def call(Map params) {

    String branchName = params.branch ?: "nmc-dev-main"
   // String k8sCloud = params.k8sCloud ?: "dev-cluster"

    podTemplate(yaml: """
kind: Pod
metadata:
  name: build-utils
spec:
  containers:
  - name: build-utils
    image: egovio/build-utils:7-master-95e76687
    imagePullPolicy: IfNotPresent
    command:
    - cat
    tty: true
    env:
      - name: DOCKER_UNAME
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: dockerUserName
      - name: DOCKER_UPASS
        valueFrom:
          secretKeyRef:
            name: jenkins-credentials
            key: dockerPassword
      - name: DOCKER_NAMESPACE
        value: "devupyog"
      - name: DOCKER_GROUP_NAME  
        value: ""
    resources:
      requests:
        memory: "768Mi"
        cpu: "250m"
      limits:
        memory: "1024Mi"
        cpu: "500m"
""") {

        node(POD_LABEL) {

            List<String> gitUrls = params.urls
            String configFile = './build/build-config.yml'

            Map<String, List<JobConfig>> jobConfigMap = [:]
            StringBuilder jobDslScript = new StringBuilder()
            List<JobConfig> allJobConfigs = []

            // Clone repo + parse config
            gitUrls.each { url ->

                String dirName = Utils.getDirName(url)

                dir(dirName) {

                    git url: url,
                        branch: branchName,
                        credentialsId: 'git_read_https'

                    def yaml = readYaml file: configFile
                    List<JobConfig> jobConfigs = ConfigParser.populateConfigs(yaml.config, env)

                    jobConfigMap.put(url, jobConfigs)
                    allJobConfigs.addAll(jobConfigs)
                }
            }

            // Create folder structure
            List<String> folders = Utils.foldersToBeCreatedOrUpdated(allJobConfigs, env)

        /*    folders.each { folderName ->
                jobDslScript.append("""
                    folder("${folderName}")
                """)
            }*/
            allJobConfigs.each { job ->

    def fullPath = "dev/${job.getName()}"
    def parts = fullPath.split('/')

    def currentPath = ""

    parts[0..-2].each { part ->

        currentPath = currentPath ? "${currentPath}/${part}" : part

        jobDslScript.append("""
            folder("${currentPath}")
        """)
    }
}

            // Create pipeline jobs
            jobConfigMap.each { repoUrl, jobConfigs ->

                jobConfigs.each { job ->

                    jobDslScript.append("""
                        pipelineJob("dev/${job.getName()}") {

                            logRotator(-1, 5, -1, -1)

                            parameters {
                                stringParam('BRANCH', '${branchName}', 'Git Branch')
                                booleanParam('ALT_REPO_PUSH', false, 'Push to alternate repo')
                            }

                            definition {
                                cpsScm {
                                    scm {
                                        git {
                                            remote {
                                                url("${repoUrl}")
                                                credentials('git_read_https')
                                            }
                                            branch('${branchName}')
                                            scriptPath('Jenkinsfile')
                                        }
                                    }
                                }
                            }
                        }
                    """)
                }
            }

            stage('Creating Jobs') {
                jobDsl scriptText: jobDslScript.toString()
            }
        }
    }
}
