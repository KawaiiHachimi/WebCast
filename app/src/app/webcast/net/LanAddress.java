package app.webcast.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Finds the LAN IPv4 address the computer should use to reach this TV. */
public final class LanAddress {
  private LanAddress() {}

  public static final class Result {
    public final String ip;
    public final String iface;
    Result(String ip, String iface) { this.ip = ip; this.iface = iface; }
  }

  public static Result find() {
    Result best = null;
    try {
      for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!ni.isUp() || ni.isLoopback()) continue;
        String name = ni.getName();
        int rank = rank(name);
        for (InetAddress a : Collections.list(ni.getInetAddresses())) {
          if (!(a instanceof Inet4Address)) continue;
          if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
          if (!a.isSiteLocalAddress()) continue;
          if (best == null || rank < rank(best.iface)) best = new Result(a.getHostAddress(), name);
        }
      }
    } catch (Exception ignored) {
      // Fall through to null; UI shows "未连接网络".
    }
    return best;
  }

  private static int rank(String iface) {
    if (iface == null) return 100;
    if (iface.startsWith("wlan")) return 0;
    if (iface.startsWith("eth")) return 1;
    if (iface.startsWith("ap")) return 2;
    if (iface.startsWith("rndis") || iface.startsWith("usb")) return 3;
    return 10;
  }
}
