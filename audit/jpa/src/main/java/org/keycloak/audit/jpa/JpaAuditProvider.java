package org.keycloak.audit.jpa;

import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.jboss.logging.Logger;
import org.keycloak.audit.AuditProvider;
import org.keycloak.audit.Event;
import org.keycloak.audit.EventQuery;
import org.keycloak.audit.EventType;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class JpaAuditProvider implements AuditProvider {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final TypeReference<Map<String, String>> mapType = new TypeReference<Map<String, String>>() {
    };
    private static final Logger logger = Logger.getLogger(JpaAuditProvider.class);

    private EntityManager em;
    private EntityTransaction tx;
    private Set<EventType> includedEvents;

    public JpaAuditProvider(EntityManager em, Set<EventType> includedEvents) {
        this.em = em;
        this.includedEvents = includedEvents;
    }

    @Override
    public EventQuery createQuery() {
        return new JpaEventQuery(em);
    }

    @Override
    public void clear() {
        beginTx();
        em.createQuery("delete from EventEntity").executeUpdate();
    }

    @Override
    public void clear(String realmId) {
        beginTx();
        em.createQuery("delete from EventEntity where realmId = :realmId").setParameter("realmId", realmId).executeUpdate();
    }

    @Override
    public void clear(String realmId, long olderThan) {
        beginTx();
        em.createQuery("delete from EventEntity where realmId = :realmId and time < :time").setParameter("realmId", realmId).setParameter("time", olderThan).executeUpdate();
    }

    @Override
    public void onEvent(Event event) {
        if (includedEvents.contains(event.getEvent())) {
            beginTx();
            em.persist(convert(event));
        }
    }

    @Override
    public void close() {
        if (tx != null) {
            tx.commit();
        }

        em.close();
    }

    private void beginTx() {
        if (tx == null) {
            tx = em.getTransaction();
            tx.begin();
        }
    }

    static EventEntity convert(Event o) {
        EventEntity e = new EventEntity();
        e.setId(UUID.randomUUID().toString());
        e.setTime(o.getTime());
        e.setEvent(o.getEvent().toString());
        e.setRealmId(o.getRealmId());
        e.setClientId(o.getClientId());
        e.setUserId(o.getUserId());
        e.setSessionId(o.getSessionId());
        e.setIpAddress(o.getIpAddress());
        e.setError(o.getError());
        try {
            e.setDetailsJson(mapper.writeValueAsString(o.getDetails()));
        } catch (IOException ex) {
            logger.error("Failed to write log details", ex);
        }
        return e;
    }

    static Event convert(EventEntity o) {
        Event e = new Event();
        e.setTime(o.getTime());
        e.setEvent(EventType.valueOf(o.getEvent()));
        e.setRealmId(o.getRealmId());
        e.setClientId(o.getClientId());
        e.setUserId(o.getUserId());
        e.setSessionId(o.getSessionId());
        e.setIpAddress(o.getIpAddress());
        e.setError(o.getError());
        try {
            Map<String, String> details = mapper.readValue(o.getDetailsJson(), mapType);
            e.setDetails(details);
        } catch (IOException ex) {
            logger.error("Failed to read log details", ex);
        }
        return e;
    }

}
