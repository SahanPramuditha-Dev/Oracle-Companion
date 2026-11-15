import hashlib
import ssl
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from companion import Gateway, Problem, certificate


class GatewayTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name)
        self.gateway = Gateway(self.path, self.path)
        self.credential = self.gateway.pair(self.gateway.issue(), "Phone")["token"]
        self.device = self.gateway.device(self.credential)

    def test_pairing_and_revoke(self):
        code = self.gateway.issue()
        self.gateway.pair(code, "Phone")
        with self.assertRaises(Problem):
            self.gateway.pair(code, "Phone")
        with self.gateway.db() as db:
            db.execute("DELETE FROM devices WHERE id=?", (self.device,))
        with self.assertRaises(Problem):
            self.gateway.device(self.credential)

    def test_chat_retry_is_idempotent_and_persistent(self):
        message = str(uuid4())
        with patch.object(self.gateway, "capabilities", return_value=("token", {
            "model": {"processing": "local", "name": "test"}
        })), patch("companion.local_json", return_value={"message": {"content": "Answer"}}) as upstream:
            self.gateway.send(message, "Question")
            self.gateway.send(message, "Question")
            self.assertEqual(upstream.call_count, 1)
        self.assertEqual([item["text"] for item in Gateway(self.path, self.path).history()["messages"]],
                         ["Question", "Answer"])

    def test_confirm_is_bound_to_phone_and_single_use(self):
        with patch.object(self.gateway, "capabilities", return_value=("token", {})), patch(
            "companion.local_json", side_effect=[
                {"id": "test-action", "expires_in_seconds": 60}, {"status": "launch_requested"}
            ]
        ) as upstream:
            self.gateway.propose(self.device)
            with self.assertRaises(Problem):
                self.gateway.confirm("different-phone", "test-action")
            result = self.gateway.confirm(self.device, "test-action")
            self.assertEqual(result["status"], "launch_requested")
            with self.assertRaises(Problem):
                self.gateway.confirm(self.device, "test-action")
            self.assertEqual(upstream.call_count, 2)
        self.assertEqual(self.gateway.activity(self.device)["events"][0]["status"], "launch_requested")

    def test_certificate_identity_survives_restart(self):
        pem, _ = certificate(self.path)
        first = hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem.read_text())).digest()
        pem, _ = certificate(self.path)
        self.assertEqual(hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem.read_text())).digest(), first)


if __name__ == "__main__":
    unittest.main()


