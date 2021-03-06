package org.keycloak.models;

import java.util.List;
import java.util.Set;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public interface UserSessionModel {

    String getId();

    void setId(String id);

    UserModel getUser();

    void setUser(UserModel user);

    String getIpAddress();

    void setIpAddress(String ipAddress);

    int getStarted();

    void setStarted(int started);

    int getLastSessionRefresh();

    void setLastSessionRefresh(int seconds);

    void associateClient(ClientModel client);

    List<ClientModel> getClientAssociations();

    void removeAssociatedClient(ClientModel client);
}
