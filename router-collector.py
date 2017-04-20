#!/usr/bin/env python
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

"""
A simple client for retrieving router metrics and exposing them
"""
from __future__ import print_function, unicode_literals

import optparse
from proton import Message, Url, ConnectionException, Timeout
from proton.utils import SyncRequestResponse, BlockingConnection
from proton.handlers import IncomingMessageHandler
import sys
from prometheus_client import start_http_server, Gauge
import random
import time
from prometheus_client.core import GaugeMetricFamily, CounterMetricFamily, REGISTRY

class MetricCollector(object):
    def __init__(self, name, description, labels, mtype="GAUGE"):
        self.name = name
        self.description = description
        self.labels = labels
        self.label_list = []
        self.value_list = []
        if mtype == "GAUGE":
            self.metric_family = GaugeMetricFamily(self.name, self.description, labels=self.labels)
        elif mtype == "COUNTER":
            self.metric_family = CounterMetricFamily(self.name, self.description, labels=self.labels)
        else:
            raise("Unknown type " + mtype)

    def add(self, label_values, value):
        for idx, val in enumerate(self.label_list):
            if val == label_values:
                self.value_list[idx] += value
                return

        self.label_list.append(label_values)
        self.value_list.append(value)

    def metric(self):
        for idx, val in enumerate(self.label_list):
            self.metric_family.add_metric(val, self.value_list[idx])
        return self.metric_family


class RouterResponse(object):
    def __init__(self, response):
        self.response = response

    def get_index(self, attribute):
        try:
            return self.response.body["attributeNames"].index(attribute)
        except ValueError:
            return None

    def get_results(self):
        return self.response.body["results"]

    def add_field(self, name, value):
        self.response.body["attributeNames"].append(name)
        for result in self.response.body["results"]:
            result.append(value)

    def add_field_from(self, name, from_field, transform):
        from_idx = self.response.body["attributeNames"].index(from_field)
        self.response.body["attributeNames"].append(name)
        for result in self.response.body["results"]:
            result.append(transform(result[from_idx]))

def clean_address(address):
    if address == None:
        return address
    elif address[0] == 'M':
        return address[2:]
    else:
        return address[1:]

def get_container_from_connections(connection_id, connections):
    container_idx = connections.get_index("container")
    id_idx = connections.get_index("identity")
    for connection in connections.get_results():
        if connection_id == connection[id_idx]:
            return connection[container_idx]
    return None

class RouterCollector(object):
    def create_collector_map(self):
        metrics = [ MetricCollector('connectionCount', 'Number of connections to router', ['routerId', 'container']),
                    MetricCollector('linkCount', 'Number of links to router', ['routerId', 'address', 'container']),
                    MetricCollector('addrCount', 'Number of addresses defined in router', ['routerId']),
                    MetricCollector('autoLinkCount', 'Number of auto links defined in router', ['routerId']),
                    MetricCollector('linkRouteCount', 'Number of link routers defined in router', ['routerId']),
                    MetricCollector('unsettledCount', 'Number of unsettled messages', ['address']),
                    MetricCollector('deliveryCount', 'Number of delivered messages', ['address'], "COUNTER"),
                    MetricCollector('releasedCount', 'Number of released messages', ['address'], "COUNTER"),
                    MetricCollector('rejectedCount', 'Number of rejected messages', ['address'], "COUNTER"),
                    MetricCollector('acceptedCount', 'Number of accepted messages', ['address'], "COUNTER"),
                    MetricCollector('undeliveredCount', 'Number of undelivered messages', ['address']),
                    MetricCollector('capacity', 'Capacity of link', ['address']) ]
        m = {}
        for metric in metrics:
            m[metric.name] = metric
        return m

    def create_entity_map(self, collector_map):
        return { self.get_router: [collector_map['connectionCount'], collector_map['linkCount'],
                                   collector_map['addrCount'], collector_map['autoLinkCount'], collector_map['linkRouteCount']],
                 self.get_connections: [collector_map['connectionCount']],
                 self.get_links: [collector_map['linkCount'], collector_map['unsettledCount'],
                                  collector_map['deliveryCount'], collector_map['releasedCount'],
                                  collector_map['rejectedCount'], collector_map['acceptedCount'],
                                  collector_map['undeliveredCount'], collector_map['capacity']] }

    def get_router(self):
        return self.collect_metric('org.apache.qpid.dispatch.router')

    def get_links(self):
        links = self.collect_metric('org.apache.qpid.dispatch.router.link')
        if links == None:
            return links

        connections = self.get_connections()
        if connections == None:
            return connections

        links.add_field_from("address", "owningAddr", clean_address)
        links.add_field("linkCount", 1)
        links.add_field_from("container", "connectionId", lambda connection_id: get_container_from_connections(connection_id, connections))

        return links

    def get_connections(self):
        response = self.collect_metric('org.apache.qpid.dispatch.connection')
        if response == None:
            return response

        response.add_field("connectionCount", 1)
        return response

    def collect(self):
        collector_map = self.create_collector_map()
        fetcher_map = self.create_entity_map(collector_map)

        for fetcher in fetcher_map:
            response = fetcher()
            if response != None:
                for collector in fetcher_map[fetcher]:
                    for entity in response.get_results():
                        labels = []
                        for l in collector.labels:
                            label_idx = response.get_index(l)
                            if label_idx != None and entity[label_idx] != None:
                                labels.append(entity[label_idx])
                            else:
                                labels.append("")
                        value = entity[response.get_index(collector.name)]
                        collector.add(labels, int(value))

        for collector in collector_map.itervalues():
            yield collector.metric()
        

    def collect_metric(self, entityType):
        try:
            client = SyncRequestResponse(BlockingConnection("127.0.0.1:5672", 30), "$management")
            try:
                properties = {}
                properties["entityType"] = entityType
                properties["operation"] = "QUERY"
                properties["name"] = "self"
                message = Message(body=None, properties=properties)
                response = client.call(message)
                if response == None:
                    return response
                else:
                    return RouterResponse(response)
            finally:
                client.connection.close()
        except:
            e = sys.exc_info()[0]
            print("Error querying router for metrics: %s" % e)
            return None

if __name__ == '__main__':
    # Start up the server to expose the metrics.
    REGISTRY.register(RouterCollector())
    start_http_server(8080)
    while True:
        time.sleep(5)
