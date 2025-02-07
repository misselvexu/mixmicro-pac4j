package org.pac4j.saml.metadata;


import net.shibboleth.shared.resolver.CriteriaSet;
import net.shibboleth.shared.resolver.ResolverException;
import org.opensaml.core.criterion.EntityIdCriterion;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.saml.metadata.resolver.MetadataResolver;
import org.opensaml.saml.metadata.resolver.impl.FilesystemMetadataResolver;
import org.pac4j.saml.config.SAML2Configuration;
import org.pac4j.saml.exceptions.SAMLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Misagh Moayyed
 * @since 1.7
 */
public class SAML2ServiceProviderMetadataResolver implements SAML2MetadataResolver {

    protected static final Logger logger = LoggerFactory.getLogger(SAML2ServiceProviderMetadataResolver.class);

    protected final SAML2Configuration configuration;
    private MetadataResolver metadataResolver;

    public SAML2ServiceProviderMetadataResolver(final SAML2Configuration configuration) {
        this.configuration = configuration;
        this.metadataResolver = prepareServiceProviderMetadata();
    }

    public void destroy() {
        if (this.metadataResolver instanceof FilesystemMetadataResolver) {
            ((FilesystemMetadataResolver) this.metadataResolver).destroy();
            this.metadataResolver = null;
        }
    }

    protected MetadataResolver prepareServiceProviderMetadata() {
        try {
            final var metadataGenerator = configuration.toMetadataGenerator();
            final var resource = configuration.getServiceProviderMetadataResource();
            if (resource == null || !resource.exists() || configuration.isForceServiceProviderMetadataGeneration()) {
                final var entity = metadataGenerator.buildEntityDescriptor();
                final var metadata = metadataGenerator.getMetadata(entity);
                metadataGenerator.storeMetadata(metadata,
                    configuration.isForceServiceProviderMetadataGeneration());
            }
            return metadataGenerator.buildMetadataResolver();
        } catch (final Exception e) {
            throw new SAMLException("Unable to generate metadata for service provider", e);
        }
    }

    @Override
    public final MetadataResolver resolve(final boolean force) {
        return this.metadataResolver;
    }

    @Override
    public final String getEntityId() {
        return configuration.getServiceProviderEntityId();
    }

    @Override
    public String getMetadata() {
        try {
            final var metadataGenerator = configuration.toMetadataGenerator();
            final var entity = metadataGenerator.buildEntityDescriptor();
            return metadataGenerator.getMetadata(entity);
        } catch (final Exception e) {
            throw new SAMLException("Unable to fetch metadata", e);
        }
    }

    @Override
    public XMLObject getEntityDescriptorElement() {
        try {
            return resolve().resolveSingle(new CriteriaSet(new EntityIdCriterion(getEntityId())));
        } catch (final ResolverException e) {
            throw new SAMLException("Unable to resolve metadata", e);
        }
    }
}
