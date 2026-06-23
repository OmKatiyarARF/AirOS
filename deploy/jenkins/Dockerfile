FROM jenkins/jenkins:lts

# Skip setup wizard since we use JCasC
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
ENV CASC_JENKINS_CONFIG=/var/jenkins_home/casc/jenkins.yaml

# Install Docker CLI and SSH client (needed for deployments)
USER root
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        docker.io \
        openssh-client \
    && rm -rf /var/lib/apt/lists/*

USER jenkins

# Install plugins from plugins.txt
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# Copy JCasC config into image (will be available at startup)
COPY casc/ /var/jenkins_home/casc/

# Copy init script that reads repos.json and creates pipeline jobs
# Jenkins copies ref/init.groovy.d/* to $JENKINS_HOME/init.groovy.d/ on startup
COPY init.groovy.d/ /usr/share/jenkins/ref/init.groovy.d/
