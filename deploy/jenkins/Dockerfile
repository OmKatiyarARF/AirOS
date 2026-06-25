FROM jenkins/jenkins:lts

# Skip setup wizard since we use JCasC
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
ENV CASC_JENKINS_CONFIG=/var/jenkins_home/casc/jenkins.yaml

# Install Node.js, npm, SSH client and the real Docker client (docker-ce-cli)
USER root
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        gnupg \
        nodejs \
        npm \
        openssh-client \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
    && chmod a+r /etc/apt/keyrings/docker.asc \
    && echo \
      "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian \
      $(. /etc/os-release && echo \"$VERSION_CODENAME\") stable" > /etc/apt/sources.list.d/docker.list \
    && apt-get update && \
    apt-get install -y --no-install-recommends \
        docker-ce-cli \
        docker-compose-plugin \
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
