package org.keycloak.models.mongo.keycloak.adapters;

import org.keycloak.models.OAuthClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.mongo.api.context.MongoStoreInvocationContext;
import org.keycloak.models.mongo.keycloak.entities.MongoOAuthClientEntity;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class OAuthClientAdapter extends ClientAdapter<MongoOAuthClientEntity> implements OAuthClientModel {

    public OAuthClientAdapter(RealmAdapter realm, MongoOAuthClientEntity oauthClientEntity, MongoStoreInvocationContext invContext) {
        super(realm, oauthClientEntity, invContext);
    }

    @Override
    public void setClientId(String id) {
        getMongoEntity().setName(id);
        updateMongoEntity();
    }

    @Override
    public boolean isDirectGrantsOnly() {
        return getMongoEntity().isDirectGrantsOnly();
    }

    @Override
    public void setDirectGrantsOnly(boolean flag) {
        getMongoEntity().setDirectGrantsOnly(flag);
    }
}
