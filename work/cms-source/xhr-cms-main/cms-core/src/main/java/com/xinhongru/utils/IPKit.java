package com.xinhongru.utils;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** ip工具类 Created at 2021/8/9. */
public class IPKit {

    /** 列出本机所有有效网卡的 IPv4 地址（排除 loopback、虚拟网卡、未启用的接口）。 */
    public static List<String> listLocalIps() {
        Set<String> ips = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> netInterfaces = NetworkInterface.getNetworkInterfaces();
            while (netInterfaces.hasMoreElements()) {
                NetworkInterface ni = netInterfaces.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback()) {
                        continue;
                    }
                    // 跳过虚拟网卡（Docker/VirtualBox/VMware 等）
                    if (ni.isVirtual()) {
                        continue;
                    }
                    String displayName =
                            ni.getDisplayName() == null ? ni.getName() : ni.getDisplayName();
                    if (isVirtualInterfaceName(ni.getName(), displayName)) {
                        continue;
                    }
                } catch (SocketException ignored) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isLoopbackAddress()) {
                        continue;
                    }
                    String host = addr.getHostAddress();
                    if (host == null || host.contains(":")) {
                        continue; // 跳过 IPv6
                    }
                    ips.add(host);
                }
            }
        } catch (SocketException e) {
            // 忽略，返回已收集的
        }
        return new ArrayList<>(ips);
    }

    /** 获取当前默认使用的本地 IP（通过向外部地址建立 Socket 试探得到，不实际发数据）。 失败时回退到 listLocalIps() 的第一个，再失败返回 null。 */
    public static String getPreferredLocalIp() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("8.8.8.8", 53), 200);
            String host = socket.getLocalAddress().getHostAddress();
            if (host != null && !host.isBlank() && !host.equals("0.0.0.0")) {
                return host;
            }
        } catch (Exception ignored) {
            // 继续走回退逻辑
        }
        List<String> ips = listLocalIps();
        return ips.isEmpty() ? null : ips.get(0);
    }

    private static boolean isVirtualInterfaceName(String name, String displayName) {
        String lower = (displayName == null ? name : displayName).toLowerCase();
        return lower.contains("docker")
                || lower.contains("veth")
                || lower.contains("virtualbox")
                || lower.contains("vmware")
                || lower.contains("virtual")
                || lower.contains("tap-")
                || lower.contains("tun0")
                || lower.startsWith("vbox")
                || lower.contains("hyper-v");
    }
}
