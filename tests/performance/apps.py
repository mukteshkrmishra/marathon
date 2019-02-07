#!/usr/bin/env python3
import asyncio
import aiohttp
import logging
import sys

from tenacity import before_log, before_sleep_log, retry, wait_fixed

logger = logging.getLogger(__name__)

MARATHON_BASE = 'http://localhost:8080'
# MARATHON='https://httpbin.org/post'


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


class AppsCollection:

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

    async def delete_all(self):
        async for app in self.all():
            await app.delete()


class Marathon:

    def __init__(self, base):
        self._base = base

    def apps(self):
        return AppsCollection(self._base)

    def pods():
        pass


def app_ids(number):
    for i in range(number):
        yield '/simpleapp-{}'.format(i)


def app_spec(app_id):
    return {"id": app_id,
            "cmd": "while [ true ] ; do echo 'Hello Marathon' ; sleep 5 ; done",
            "cpus": 0.1,
            "mem": 10.0,
            "instances": 1,
            "executor": "/opt/shared/marathon_performance_executor-1.5.1",
            "labels": {"MARATHON_EXECUTOR_ID": "custom-executor"}
            }


async def create_apps(number):
    client = Marathon(MARATHON_BASE)
    for app_id in app_ids(number):
        spec = app_spec(app_id)

        @retry(before_sleep=before_sleep_log(logger, logging.DEBUG), before=before_log(logger, logging.DEBUG), wait=wait_fixed(2))
        async def create():
            await client.apps().create(spec)

        # Fire and forget.
        asyncio.ensure_future(create())


if __name__ == "__main__":
    logging.basicConfig(level=logging.DEBUG)
    loop = asyncio.get_event_loop()

    command = sys.argv[1]
    if command == "create":
        apps = int(sys.argv[2])
        loop.run_until_complete(create_apps(apps))
    else:
        client = Marathon(MARATHON_BASE)
        loop.run_until_complete(client.apps().delete_all())

    # Let's also finish all running tasks:
    pending = asyncio.Task.all_tasks()
    loop.run_until_complete(asyncio.gather(*pending))

    loop.close()
