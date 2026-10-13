package main

type TunnelState string

const (
	StateDisconnected  TunnelState = "disconnected"
	StateConnecting    TunnelState = "connecting"
	StateConnected     TunnelState = "connected"
	StateDisconnecting TunnelState = "disconnecting"
	StateError         TunnelState = "error"
)

type StartRequest struct {
	Mode          string        `json:"mode"` // "tun" or "proxy"
	Profile       ProfileConfig `json:"profile"`
	ProxyPort     int           `json:"proxy_port"`      // default 10808 (SOCKS5)
	HttpProxyPort int           `json:"http_proxy_port"` // default 10809 (HTTP)
	BypassRu      bool          `json:"bypass_ru"`
	BypassLan     bool          `json:"bypass_lan"`
	CustomDNS     string        `json:"custom_dns"`
	CookieHeader  string        `json:"cookie_header,omitempty"`
	UseCookieAuth bool          `json:"use_cookie_auth,omitempty"`
}

type ProfileConfig struct {
	Name          string          `json:"name"`
	VKLink        string          `json:"vk_link"`
	UseSRTP       bool            `json:"use_srtp"`
	UseDTLS       bool            `json:"use_dtls"`
	UseWrap       bool            `json:"use_wrap"`
	WrapKey       string          `json:"wrap_key"`
	NumConns      int             `json:"num_conns"`
	UseCookieAuth bool            `json:"use_cookie_auth,omitempty"`
	WireGuard     WireGuardConfig `json:"wireguard"`
}

type WireGuardConfig struct {
	PrivateKey   string `json:"private_key"`
	Address      string `json:"address"`
	DNS          string `json:"dns"`
	PeerPublicKey string `json:"peer_public_key"`
	Endpoint     string `json:"endpoint"`
	AllowedIPs   string `json:"allowed_ips"`
	MTU          int    `json:"mtu"`
	PreSharedKey string `json:"preshared_key"`
}

type StatusResponse struct {
	State         TunnelState `json:"state"`
	Error         string      `json:"error,omitempty"`
	ProfileName   string      `json:"profile_name,omitempty"`
	Mode          string      `json:"mode,omitempty"`
	BytesRx       uint64      `json:"bytes_rx"`
	BytesTx       uint64      `json:"bytes_tx"`
	SpeedRx       uint64      `json:"speed_rx"`
	SpeedTx       uint64      `json:"speed_tx"`
	LatencyMs     int64       `json:"latency_ms"`
	UptimeSec     int64       `json:"uptime_sec"`
	LocalIP       string      `json:"local_ip,omitempty"`
	ProxyAddr     string      `json:"proxy_addr,omitempty"`
	HttpProxyAddr string      `json:"http_proxy_addr,omitempty"`
	TurnServerIP  string      `json:"turn_server_ip,omitempty"`
}

type SpeedTestResult struct {
	DownloadMbps float64 `json:"download_mbps"`
	UploadMbps   float64 `json:"upload_mbps"`
	PingMs       float64 `json:"ping_ms"`
	Server       string  `json:"server"`
	Error        string  `json:"error,omitempty"`
}
