package com.cloudbees.jenkins.plugins.docker_build_env;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.Extension;
import hudson.model.*;
import hudson.util.ListBoxModel;
import jenkins.authentication.tokens.api.AuthenticationTokens;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.docker.commons.credentials.DockerRegistryToken;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

/**
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class PullDockerImageSelector extends DockerImageSelector {

    private String image;
    private String dockerRegistryCredentials;

    @DataBoundConstructor
    public PullDockerImageSelector(String image, String dockerRegistryCredentials) {
        this.image = image;
        this.dockerRegistryCredentials = dockerRegistryCredentials;
    }

    public PullDockerImageSelector(String image) {
        this(image, null);
    }

    public String getDockerRegistryCredentials() {
        return dockerRegistryCredentials;
    }

    public String getImage() {
        return image;
    }

    @Override
    public String prepareDockerImage(Docker docker,
                                     AbstractBuild build,
                                     TaskListener listener,
                                     boolean forcePull) throws IOException, InterruptedException {
        String expandedImage = build.getEnvironment(listener).expand(image);
        if (forcePull || !docker.hasImage(expandedImage)) {
            listener.getLogger().println("Pull Docker image " + expandedImage + " from repository ...");
            boolean pulled = docker.pullImage(expandedImage);
            if (!pulled) {
                listener.getLogger().println("Failed to pull Docker image " + expandedImage);
                throw new IOException("Failed to pull Docker image " + expandedImage);
            }
        }
        return expandedImage;
    }

    @Override
    public Collection<String> getDockerImagesUsedByJob(Job<?, ?> job) {
        return Collections.singleton(image);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<DockerImageSelector> {

        public ListBoxModel doFillDockerRegistryCredentialsItems(@AncestorInPath Item item,
                                                                 @QueryParameter String uri) {
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withMatching(
                            AuthenticationTokens.matcher(DockerRegistryToken.class),
                            CredentialsProvider.lookupCredentials(
                                    StandardCredentials.class,
                                    item,
                                    null,
                                    Collections.<DomainRequirement>emptyList()
                            )
                    );
        }

        @Override
        @Nonnull
        public String getDisplayName() {
            return "Pull docker image from repository";
        }

        public Collection<Descriptor<DockerImageSelector>> selectors() {
            return Jenkins.getInstance().getDescriptorList(DockerImageSelector.class);
        }
    }
}
