import unittest

from rtc.server.rtc_server.sdp import AdvertisedCandidateRange, rewrite_answer_sdp_for_phone_proxy


class RewriteAnswerSdpForPhoneProxyTest(unittest.TestCase):
    def test_rewrites_video_media_section_to_fixed_proxy_endpoint(self) -> None:
        original_sdp = (
            "v=0\r\n"
            "o=- 1 1 IN IP4 127.0.0.1\r\n"
            "s=-\r\n"
            "t=0 0\r\n"
            "m=video 51243 UDP/TLS/RTP/SAVPF 96\r\n"
            "c=IN IP4 10.38.204.90\r\n"
            "a=mid:0\r\n"
            "a=candidate:1 1 udp 2130706431 10.38.204.90 51243 typ host\r\n"
            "a=end-of-candidates\r\n"
        )

        rewritten = rewrite_answer_sdp_for_phone_proxy(
            original_sdp,
            AdvertisedCandidateRange(host="192.168.1.83", port_start=40012, port_end=40012),
        )

        self.assertIn("m=video 40012 UDP/TLS/RTP/SAVPF 96\r\n", rewritten)
        self.assertIn("c=IN IP4 192.168.1.83\r\n", rewritten)
        self.assertIn("a=candidate:1 1 udp 2130706431 192.168.1.83 40012 typ host\r\n", rewritten)
        self.assertEqual(1, rewritten.count("a=end-of-candidates\r\n"))


if __name__ == "__main__":
    unittest.main()
