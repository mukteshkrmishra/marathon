#!/usr/bin/env python3
from abc import ABC, abstractmethod
import asyncio
import aiohttp
import logging
import sys

from tenacity import before_log, before_sleep_log, retry, wait_random

logger = logging.getLogger(__name__)

MARATHON_BASE = 'http://localhost:8080'
# MARATHON='https://httpbin.org/post'


class Collection(ABC):

    @abstractmethod
    async def all(self):
        pass

    @abstractmethod
    async def create(self, spec):
        pass

    async def delete_all(self):
        async for app in self.all():
            await app.delete()


class AppObject:

    def __init__(self, base, app):
        self._base = base
        self._app = app

    async def delete(self):
        async with aiohttp.ClientSession() as session:
            app_id = self._app['id']
            logger.info('Deleting %s...', app_id)
            path = '{}/v2/apps/{}'.format(self._base, app_id)
            async with session.delete(path) as resp:
                logger.info('Done deleting %s: %d', app_id, resp.status)
                await resp.text()


class PodObject:

    def __init__(self, base, pod):
        self._base = base
        self._pod = pod

    async def delete(self):
        async with aiohttp.ClientSession() as session:
            pod_id = self._pod['id']
            logger.info('Deleting %s...', pod_id)
            path = '{}/v2/pods/{}'.format(self._base, pod_id)
            async with session.delete(path) as resp:
                logger.info('Done deleting %s: %d', pod_id, resp.status)
                await resp.text()


class Apps(Collection):

    def __init__(self, base):
        self._base = base

    async def all(self):
        async with aiohttp.ClientSession() as session:
            logger.info('Fetching all apps...')
            async with session.get("{}/v2/apps".format(self._base)) as resp:
                apps = await resp.json()
                for app_spec in apps['apps']:
                    yield AppObject(self._base, app_spec)

    async def create(self, app_spec):
        async with aiohttp.ClientSession() as session:
            app_id = app_spec['id']
            logger.info('Posting app %s', app_id)
            async with session.post("{}/v2/apps".format(self._base), json=app_spec) as resp:
                assert resp.status == 201, 'Marathon replied with {}:{}'.format(resp.status, await resp.text())
                logger.info('Done posting %s: %d', app_id, resp.status)
                return AppObject(self._base, app_spec)


class Pods(Collection):
    def __init__(self, base):
        self._base = base

    async def all(self):
        async with aiohttp.ClientSession() as session:
            logger.info('Fetching all pods...')
            async with session.get("{}/v2/pods".format(self._base)) as resp:
                pods = await resp.json()
                for pod_spec in pods:
                    yield PodObject(self._base, pod_spec)

    async def create(self, pod_spec):
        async with aiohttp.ClientSession() as session:
            pod_id = pod_spec['id']
            logger.info('Posting pod %s', pod_id)
            async with session.post("{}/v2/pods".format(self._base), json=pod_spec) as resp:
                assert resp.status == 201, 'Marathon replied with {}:{}'.format(resp.status, await resp.text())
                logger.info('Done posting %s: %d', pod_id, resp.status)
                return PodObject(self._base, pod_spec)


class Marathon:

    def __init__(self, base):
        self._base = base

    def apps(self):
        return Apps(self._base)

    def pods(self):
        return Pods(self._base)


def app_ids(number):
    for i in range(number):
        yield '/simpleapp-{}'.format(i)


def pod_ids(number):
    for i in range(number):
        yield '/simplepod-{}'.format(i)


def app_spec(app_id):
    return {"id": app_id,
            "cmd": "sleep infinity",
            "cpus": 0.1,
            "mem": 10.0,
            "instances": 1
            # "executor": "/opt/shared/marathon_performance_executor-1.5.1",
            # "labels": {"MARATHON_EXECUTOR_ID": "custom-executor"}
            }


def pod_spec(pod_id):
    return { "id": pod_id,
             "scaling": { "kind": "fixed", "instances": 1 },
	     "containers": [
                { "name": "sleep1",
	          "exec": { "command": { "shell": "sleep infinity" } },
	          "resources": { "cpus": 0.1, "mem": 10.0 }
                }
	     ],
	     "networks": [ {"mode": "host"} ]
	  }


async def create_apps(number):
    client = Marathon(MARATHON_BASE)
    for app_id in app_ids(number):
        logger.info('Create app with id %s', app_id)
        spec = app_spec(app_id)

        @retry(before_sleep=before_sleep_log(logger, logging.DEBUG), before=before_log(logger, logging.DEBUG),
               wait=wait_random(min=3, max=5))
        async def create(spec):
            await client.apps().create(spec)

        # Fire and forget.
        asyncio.ensure_future(create(spec))


async def create_pods(number):
    client = Marathon(MARATHON_BASE)
    for pod_id in pod_ids(number):
        logger.info('Create pod with id %s', pod_id)
        spec = pod_spec(pod_id)

        @retry(before_sleep=before_sleep_log(logger, logging.DEBUG), before=before_log(logger, logging.DEBUG),
               wait=wait_random(min=3, max=5))
        async def create(spec):
            await client.pods().create(spec)

        # Fire and forget.
        asyncio.ensure_future(create(spec))


if __name__ == "__main__":
    logging.basicConfig(level=logging.DEBUG)
    loop = asyncio.get_event_loop()

    command = sys.argv[1]
    if command == "create":
        typ = sys.argv[2]
        count = int(sys.argv[3])
        if typ == 'apps':
            loop.run_until_complete(create_apps(count))
        else:
            loop.run_until_complete(create_pods(count))
    else:
        client = Marathon(MARATHON_BASE)
        loop.run_until_complete(client.apps().delete_all())
        loop.run_until_complete(client.pods().delete_all())

    # Let's also finish all running tasks:
    pending = asyncio.Task.all_tasks()
    loop.run_until_complete(asyncio.gather(*pending))

    loop.close()
