import json
import logging
import requests
import sys

HOST = 'http://localhost:3000'

logger = logging.getLogger('rest-test')


def info_line():
    logger.info('------------------------')


def tests_info(name):
    info_line()
    logger.info(" <TEST: {}>".format(name))


def req(method, url, data):
    headers = {'content-type': 'application/json'}
    if data:
        return requests.request(method, url, data=json.dumps(data), headers=headers)
    else:
        return requests.request(method, url, headers=headers)


def test_endpoint(test):
    method, url, data, expected = test
    info_line()
    logger.info(' * Testing [{}] {} with {}'.format(method, url, data))
    res = req(method, HOST + url, data)
    if res.status_code != 200:
        logger.error('Status code was not 200.')
        logger.error('Status: {} Text: {}'.format(res.status_code, res.text))
        exit(1)
    logger.debug('Received headers: {}'.format(json.dumps(dict(res.headers))))
    if expected is False:
        logger.info('Response was ok. No expectation.')
        return
    if 'application/json' in res.headers.get('Content-type', {}) and res.text:
        result = res.json()
    else:
        result = res.text
    if expected != result:
        logger.info('Received text: {}'.format(result))
        logger.info('Expected value: {}'.format(json.dumps(expected)))
        exit(1)
    else:
        logger.info('Passed expectation.')


def test_root():
    # Clean members table beforehand.
    tests_info("root")
    to_create = {"first_name": "john",
                 "last_name": "smith", "email": "john@yo.com"}
    created = {}
    created.update(to_create)
    created.update({'id': 1})
    to_update = {"first_name": "mike",
                 "last_name": "breker", "email": "mike@jazz.com"}
    updated = {}
    updated.update(to_update)
    updated.update({'id': 1})

    tests = [
        # members: create, list
        ('post', '/members', to_create, ''),
        ('get', '/members', None, [created]),
        # members: fetch, patch, put and delete
        ('get', '/members/1', None, created),
        ('patch', '/members/1', to_update, ''),
        ('get', '/members/1', None, updated),
        ('put', '/members/1', to_create, ''),
        ('get', '/members/1', None, created),
        ('delete', '/members/1', None, ''),
        ('get', '/members', None, [])]

    for t in tests:
        test_endpoint(t)


def test_one_n():
    # Clean venues & meetups tables beforehand.
    tests_info("one-to-n")
    venue_to_create = {'name': 'city hall', 'postal_code': '123456',
                       'prefecture': 'gifu', 'city': 'gujo', 'street1': '1',
                       'street2': '123', 'building': 'shrine'}
    venue_created = {}
    venue_created.update(venue_to_create)
    venue_created.update({'id': 1})

    meetup_to_create = {"title": "ward 2 gathering", "start_at": "2020-01-01 09:00:00",
                        "end_at": "2020-01-01 10:00:00"}
    meetup_created = {}
    meetup_created.update(meetup_to_create)
    meetup_created.update({'id': 1, 'venue_id': 1})

    meetup_to_update = {"title": "ward 2 party", "start_at": "2020-01-01 23:00:00",
                        "end_at": "2020-01-01 23:59:59", "venue_id": 2}
    meetup_updated = {}
    meetup_updated.update(meetup_to_update)
    meetup_updated.update({'id': 1})

    meetup_2nd_updated = {}
    meetup_2nd_updated.update(meetup_created)
    meetup_2nd_updated.update({'venue_id': 2})

    tests = [
        # venue: create and fetch
        ('post', '/venues', venue_to_create, ''),
        ('get', '/venues/1', None, venue_created),
        # meetup: create and list
        ('post', '/venues/1/meetups', meetup_to_create, ''),
        ('get', '/venues/1/meetups', None, [meetup_created]),
        # meetup: fetch, patch, put and delete
        ('get', '/venues/1/meetups/1', None, meetup_created),
        ('patch', '/venues/1/meetups/1', meetup_to_update, ''),
        ('get', '/venues/1/meetups/1', None, ''),
        ('get', '/venues/2/meetups/1', None, meetup_updated),
        ('get', '/venues/2/meetups/1', None, meetup_updated),
        ('put', '/venues/2/meetups/1', meetup_to_create, ''),
        ('get', '/venues/2/meetups/1', None, meetup_2nd_updated),
        ('delete', '/venues/2/meetups/1', None, ''),
        ('get', '/venues/2/meetups/1', None, ''),
        # cleanup
        ('delete', '/venues/1', None, ''), ]

    for t in tests:
        test_endpoint(t)


def test_n_n():
    tests_info("n-to-n")
    venue_to_create = {'name': 'city hall', 'postal_code': '123456',
                       'prefecture': 'gifu', 'city': 'gujo', 'street1': '1',
                       'street2': '123', 'building': 'shrine'}
    venue_created = {}
    venue_created.update(venue_to_create)
    venue_created.update({'id': 1})

    member_to_create = {"first_name": "john",
                        "last_name": "smith", "email": "john@yo.com"}
    member_created = {}
    member_created.update(member_to_create)
    member_created.update({'id': 1})

    member_2nd_created = {}
    member_2nd_created.update(member_to_create)
    member_2nd_created.update({'id': 2})

    meetup_to_create = {"title": "ward 2 gathering", "start_at": "2020-01-01 09:00:00",
                        "end_at": "2020-01-01 10:00:00"}
    meetup_created = {}
    meetup_created.update(meetup_to_create)
    meetup_created.update({'id': 1, 'venue_id': 1})

    meetup_2nd_created = {}
    meetup_2nd_created.update(meetup_to_create)
    meetup_2nd_created.update({'id': 2, 'venue_id': 1})

    tests = [
        # members: create and fetch 2 items
        ('post', '/members', member_to_create, ''),
        ('post', '/members', member_to_create, ''),
        ('get', '/members/1', None, member_created),
        ('get', '/members/2', None, member_2nd_created),
        # venues: create and fetch
        ('post', '/venues', venue_to_create, ''),
        ('get', '/venues/1', None, venue_created),
        # meetups: create and fetch 2 items
        ('post', '/venues/1/meetups', meetup_to_create, ''),
        ('post', '/venues/1/meetups', meetup_to_create, ''),
        ('get', '/venues/1/meetups/1', None, meetup_created),
        ('get', '/venues/1/meetups/2', None, meetup_2nd_created),
        # meetups_members: add
        ('post', '/meetups/2/members/1/add', None, ''),
        ('post', '/meetups/1/members/2/add', None, ''),
        ('get', '/meetups/2/members', None, [member_created]),
        ('get', '/meetups/1/members', None, [member_2nd_created]),
        ('get', '/members/2/meetups', None, [meetup_created]),
        ('get', '/members/1/meetups', None, [meetup_2nd_created]),
        # meetups_members: delete member 1 from meetup 2
        ('post', '/meetups/2/members/1/delete', None, ''),
        ('get', '/meetups/2/members', None, []),
        ('get', '/members/1/meetups', None, []),
        ('get', '/meetups/1/members', None, [member_2nd_created]),
        ('get', '/members/2/meetups', None, [meetup_created]),
        # meetups_members: delete member 2 from meetup 1
        ('post', '/meetups/1/members/2/delete', None, ''),
        ('get', '/meetups/2/members', None, []),
        ('get', '/meetups/1/members', None, []),
        ('get', '/members/2/meetups', None, []),
        ('get', '/members/1/meetups', None, []),
        # cleanup
        ('delete', '/members/1', None, ''),
        ('delete', '/members/2', None, ''),
        ('delete', '/meetups/1', None, ''),
        ('delete', '/meetups/2', None, ''),
        ('delete', '/venues/1', None, ''), ]

    for t in tests:
        test_endpoint(t)


def main(args):
    if len(args) > 1 and args[1] == '-v':
        log_level = logging.DEBUG
    else:
        log_level = logging.INFO
    logging.basicConfig(level=log_level)
    test_root()
    test_one_n()
    test_n_n()


if __name__ == "__main__":
    main(sys.argv)
