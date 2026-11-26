package com.xinhongru.security.identity;

import java.io.Serializable;

/**
 * Redis session value containing identity only; never stores an account password or permissions.
 */
public record SessionPrincipal(Integer id, Realm realm) implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Realm {
        ADMIN,
        MEMBER
    }

    public SessionPrincipal {
        if (id == null || id <= 0 || realm == null)
            throw new IllegalArgumentException("Invalid session principal");
    }
}
