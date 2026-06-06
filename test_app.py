import httpx
import asyncio

async def main():
    try:
        async with httpx.AsyncClient() as client:
            resp = await client.post("http://localhost:8080/api/chat", json={"prompt": "hello", "conversation_id": "123"})
            print(resp.status_code, resp.text)
    except Exception as e:
        import traceback
        traceback.print_exc()

asyncio.run(main())
