import json
import logging
import requests

HOST = 'http://localhost:3000'

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger('rest-test')


def req(method, url, data):
    headers = {'content-type': 'application/json'}
    if data:
        return requests.request(method, url, data=json.dumps(data), headers=headers)
    else:
        return requests.request(method, url, headers=headers)


def test_endpoint(test):
    method, url, data, expected = test
    logger.info('------------------------')
    logger.info('* Testing {} {} with {}'.format(method, url, data))
    res = req(method, url, data)
    if res.status_code != 200:
        logger.error('Status code was not 200.')
        logger.error('Status: {} Text: {}'.format(res.status_code, res.text))
        exit(1)
    logger.info('Received headers: {}'.format(json.dumps(dict(res.headers))))
    logger.info('Received text: {}'.format(res.text))
    if not expected:
        logger.info('Response was ok. No expectation.')
        return
    logger.info('Expected value: {}'.format(json.dumps(expected)))
    if 'application/json' in res.headers.get('Content-type') and res.text:
        assert expected == res.json()
    else:
        assert expected == res.text
    logger.info('Passed expectation.')


def test_root():
    # Clean members table beforehand.
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

    tests = [('post', HOST + '/members', to_create, None),
             ('get', HOST + '/members', None, [created]),
             ('get', HOST + '/members/1', None, created),
             ('patch', HOST + '/members/1', to_update, None),
             ('get', HOST + '/members/1', None, updated),
             ('put', HOST + '/members/1', to_create, None),
             ('get', HOST + '/members/1', None, created),
             ('delete', HOST + '/members/1', None, None),
             ('get', HOST + '/members', None, [])]

    for t in tests:
        test_endpoint(t)


def test_one_n():
    # Clean venues & meetups tables beforehand.
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

    tests = [('post', HOST + '/venues', venue_to_create, None),
             ('get', HOST + '/venues', None, [venue_created]),
             ('post', HOST + '/venues/1/meetups', meetup_to_create, None),
             ('get', HOST + '/venues/1/meetups', None, [meetup_created]),
             ('get', HOST + '/venues/1/meetups/1', None, meetup_created),
             ('patch', HOST + '/venues/1/meetups/1', meetup_to_update, None),
             ('get', HOST + '/venues/2/meetups/1', None, meetup_updated),
             ('put', HOST + '/venues/2/meetups/1', meetup_to_create, None),
             ('get', HOST + '/venues/2/meetups/1', None, meetup_2nd_updated),
             ('delete', HOST + '/venues/2/meetups/1', None, None),
             ('get', HOST + '/venues/2/meetups/1', None, [])]

    for t in tests:
        test_endpoint(t)


def main():
    test_root()
    test_one_n()


if __name__ == "__main__":
    main()
