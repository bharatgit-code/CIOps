import org.egov.jenkins.ConfigParser
import org.egov.jenkins.Utils
import org.egov.jenkins.models.JobConfig
import org.egov.jenkins.models.BuildConfig

def call(Map params) {

    podTemplate(cloud: 'jenkins-build', yaml: """
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
        value: devupyog
      - name: DOCKER_GROUP_NAME  
        value: dev
    resources:
      requests:
        memory: "768Mi"
        cpu: "250m"
      limits:
        memory: "1024Mi"
        cpu: "500m"                
"""
    ) {
        node(POD_LABEL) {
        
        List<String> gitUrls = params.urls;
        String configFile = './build/build-config.yml';
        Map<String,List<JobConfig>> jobConfigMap=new HashMap<>();
        StringBuilder jobDslScript = new StringBuilder();
        List<String> allJobConfigs = new ArrayList<>();

        for (int i = 0; i < gitUrls.size(); i++) {
            String dirName = Utils.getDirName(gitUrls[i]);
            dir(dirName) {
                 git url: gitUrls[i], credentialsId: 'git_read'
                 def yaml = readYaml file: configFile;
                 List<JobConfig> jobConfigs = ConfigParser.populateConfigs(yaml.config, env);
                 jobConfigMap.put(gitUrls[i],jobConfigs);
                 allJobConfigs.addAll(jobConfigs);
            }
        }
        
        Set<String> repoSet = new HashSet<>();
        String repoList = "";

        List<String> folders = Utils.foldersToBeCreatedOrUpdated(allJobConfigs, env);
                  for (int j = 0; j < folders.size(); j++) {
                      jobDslScript.append("""
                          folder("${folders[j]}")
                          """);
                    }

        for (Map.Entry<String, List<JobConfig>> entry : jobConfigMap.entrySet()) {   

            List<JobConfig> jobConfigs = entry.getValue();

        for (int i = 0; i < jobConfigs.size(); i++) {

            for(int j=0; j<jobConfigs.get(i).getBuildConfigs().size(); j++){
                BuildConfig buildConfig = jobConfigs.get(i).getBuildConfigs().get(j);
                repoSet.add(buildConfig.getImageName());                    
            }  

            repoList = String.join(",", repoSet);     

            def targetBranches = [
                [name: 'dev', branch: 'nmc-dev-main'],
                [name: 'staging', branch: 'nmc-staging-main']
            ];

            targetBranches.each { b ->
                String envJobName = "${jobConfigs.get(i).getName()}/${b.name}";
                jobDslScript.append("""
            pipelineJob("${envJobName}") {
                logRotator(-1, 5, -1, -1)
                parameters {  
                  booleanParam('ALT_REPO_PUSH', false, 'Check to push images to GCR')
            }
                definition {
                    cpsScm {
                        scm {
                            git{
                                remote {
                                    url("${entry.getKey()}")
                                    credentials('git_read')
                                } 
                                branch ('${b.branch}')
                                scriptPath('Jenkinsfile-nmc')
                                extensions { }
                            }
                        }

                    }
                }
            }
""");
            }
        }
        }

        stage('Building jobs') {
           jobDsl scriptText: jobDslScript.toString()
        }

        stage('Creating Repositories in DockerHub') {
                    withEnv(["REPO_LIST=${repoList}"
                    ]) {
                        container(name: 'build-utils', shell: '/bin/sh') {
                            sh (script:'sh /tmp/scripts/create_repo.sh')
                           //sh (script:'echo \$REPO_LIST')
                        }
                    }
        }
                

    }

}
}
