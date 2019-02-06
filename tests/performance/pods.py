#!/usr/bin/env python3
import asyncio
import aiohttp
import logging
import sys

from tenacity import before_log, retry, wait_fixed

logger = logging.getLogger(__name__)

MARATHON_BASE='http://localhost:8080'
#MARATHON='https://httpbin.org/post'

def pod_ids(number):
    for i in range(number):
        yield "/simplepod-{}".format(i)


def pod_spec(pod_id):
    return { "id": pod_id,
             "scaling": { "kind": "fixed", "instances": 0 },
	     "containers": [
                { "name": "sleep1",
	          "exec": { "command": { "shell": "sleep 1000" } },
	          "resources": { "cpus": 0.1, "mem": 32 }
                }
	     ],
	     "networks": [ {"mode": "host"} ]
	  }

@retry(before=before_log(logger, logging.DEBUG), wait=wait_fixed(2))
async def create_pod(pod_spec):
    async with aiohttp.ClientSession() as session:
        pod_id = pod_spec['id']
        logger.info('Posting pod %s', pod_id)
        async with session.post("{}/v2/pods".format(MARATHON_BASE), json=pod_spec) as resp:
            assert resp.status == 201, 'Marathon replised with {}:{}'.format(resp.status, await resp.text())
            logger.info('Done posting %s: %d', pod_id, resp.status)


async def delete_pod(pod_id):
    async with aiohttp.ClientSession() as session:
        path = '{}/v2/pods/{}'.format(MARATHON_BASE, pod_id)
        print('Deleting pod {}'.format(path))
        async with session.delete(path) as resp:
            logger.info('Done deleting %s: %d', pod_id, resp.status)


async def get_pods():
    async with aiohttp.ClientSession() as session:
        async with session.get("{}/v2/pods".format(MARATHON_BASE)) as resp:
            return await resp.json()


async def create_pods(number):
    for pod_id in pod_ids(number):
        spec = pod_spec(pod_id)
        # Fire and forget.
        asyncio.ensure_future(create_pod(spec))


async def delete_pods():
    pods = await get_pods()
    pod_ids = [ pod['id'] for pod in pods ]
    logger.info('Deleting %d pods', len(pod_ids))
    for pod_id in pod_ids:
        # Fire and forget.
        asyncio.ensure_future(delete_pod(pod_id))


if __name__ == "__main__":
    logging.basicConfig(level=logging.DEBUG)
    loop = asyncio.get_event_loop()

    command = sys.argv[1]
    if command == "create":
        pods = int(sys.argv[2])
        loop.run_until_complete(create_pods(pods))
    else:
        loop.run_until_complete(delete_pods())


    # Let's also finish all running tasks:
    pending = asyncio.Task.all_tasks()
    loop.run_until_complete(asyncio.gather(*pending))

    loop.close()
