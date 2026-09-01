import asyncio
import ssl
from unittest.mock import AsyncMock, patch

import pytest

from axonbase import Axon, CertificateChallenge, CertificateCompletion, METHODS


def test_certificate_types_and_methods_are_public():
    assert "certificate.begin" in METHODS
    assert "certificate.complete" in METHODS
    assert CertificateChallenge("challenge-id", "nonce", "2026-01-01T00:00:00Z").id == "challenge-id"
    assert CertificateCompletion(
        id="challenge-id",
        challenge="nonce",
        store="icp-brasil",
        user="12345678901",
        chain=["DER-base64"],
        signature="signature-base64",
    ).store == "icp-brasil"


@pytest.mark.asyncio
async def test_certificate_begin_sends_store_and_returns_typed_challenge():
    client = Axon("wss://example.test/rpc/ws")
    client._call = AsyncMock(return_value={
        "id": "challenge-id",
        "challenge": "nonce",
        "expires_at": "2026-01-01T00:00:00Z",
    })

    result = await client.certificate_begin("icp-brasil")

    assert result == CertificateChallenge("challenge-id", "nonce", "2026-01-01T00:00:00Z")
    client._call.assert_awaited_once_with("certificate.begin", [{"store": "icp-brasil"}])


@pytest.mark.asyncio
async def test_certificate_complete_sends_completion_and_returns_credential():
    client = Axon("wss://example.test/rpc/ws")
    completion = CertificateCompletion(
        id="challenge-id",
        challenge="nonce",
        store="icp-brasil",
        user="12345678901",
        chain=["leaf-der-base64", "issuer-der-base64"],
        signature="signature-base64",
    )
    client._call = AsyncMock(return_value="temporary-credential")

    result = await client.certificate_complete(completion)

    assert result == "temporary-credential"
    client._call.assert_awaited_once_with("certificate.complete", [{
        "id": "challenge-id",
        "challenge": "nonce",
        "store": "icp-brasil",
        "user": "12345678901",
        "chain": ["leaf-der-base64", "issuer-der-base64"],
        "signature": "signature-base64",
    }])


@pytest.mark.asyncio
async def test_open_websocket_uses_supplied_ssl_context():
    context = ssl.create_default_context()
    websocket = AsyncMock()
    websocket.recv.return_value = '{"hello":{"protocol":1}}'
    client = Axon("wss://example.test/rpc/ws", ssl_context=context)

    with patch("axonbase.client.websockets.connect", new=AsyncMock(return_value=websocket)) as connect, \
         patch.object(Axon, "_listen", new=AsyncMock()):
        await client._open_ws()
        await asyncio.sleep(0)

    connect.assert_awaited_once_with("wss://example.test/rpc/ws", ssl=context)
    assert client._loop is asyncio.get_running_loop()
