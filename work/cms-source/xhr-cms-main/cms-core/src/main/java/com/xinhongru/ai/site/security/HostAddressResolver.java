package com.xinhongru.ai.site.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

@FunctionalInterface
public interface HostAddressResolver {
    List<InetAddress> resolve(String host) throws UnknownHostException;

    static HostAddressResolver system() {
        return host -> List.of(InetAddress.getAllByName(host));
    }
}
