package org.matsim.contrib.josm;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.josm.scenario.EditableScenario;
import org.matsim.contrib.josm.scenario.EditableScenarioUtils;
import org.matsim.contrib.josm.scenario.EditableTransitLine;
import org.matsim.contrib.josm.scenario.EditableTransitRoute;
import org.matsim.contrib.josm.scenario.EditableTransitStopFacility;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.LinkImpl;
import org.matsim.core.network.MatsimNetworkReader;
import org.matsim.core.network.NodeImpl;
import org.matsim.core.population.routes.LinkNetworkRouteImpl;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.WaySegment;

class Importer {

	private final File network;
	private final File schedule;
	private MATSimLayer layer;

	HashMap<Id<TransitStopFacility>, TransitStopFacility> stops = new HashMap<>();
	HashMap<Way, List<Link>> way2Links = new HashMap<>();
	HashMap<Link, List<WaySegment>> link2Segment = new HashMap<>();
	HashMap<Node, org.openstreetmap.josm.data.osm.Node> node2OsmNode = new HashMap<>();
	HashMap<Id<Link>, Way> linkId2Way = new HashMap<>();
	HashMap<Relation, TransitStopFacility> stopRelation2TransitStop = new HashMap<>();
	private DataSet dataSet;
	private EditableScenario sourceScenario;
	private EditableScenario targetScenario;

	public Importer(File network, File schedule) {
		this.network = network;
		this.schedule = schedule;
	}

	public Importer(EditableScenario scenario) {
		this.sourceScenario = scenario;
		this.network = null;
		this.schedule = null;
	}

	void run() {
		dataSet = new DataSet();
		if (sourceScenario == null) {
			sourceScenario = readScenario();
		}
		copyIdsToOrigIds(sourceScenario);
		targetScenario = EditableScenarioUtils.createScenario(sourceScenario.getConfig());
		convertNetwork();
		if (sourceScenario.getConfig().transit().isUseTransit()) {
			convertStops();
			convertLines();
		}
		layer = new MATSimLayer(dataSet, network == null ? MATSimLayer.createNewName() : network.getName(), network == null ? null : network, targetScenario, way2Links, link2Segment, stopRelation2TransitStop);
	}

	// Abuse fields in MATSim data structures to hold the "real" object ids.
	private void copyIdsToOrigIds(EditableScenario sourceScenario) {
		for (Node node : sourceScenario.getNetwork().getNodes().values()) {
			((NodeImpl) node).setOrigId(node.getId().toString());
		}
		for (Link link : sourceScenario.getNetwork().getLinks().values()) {
			((LinkImpl) link).setOrigId(link.getId().toString());
		}
		if (sourceScenario.getConfig().transit().isUseTransit()) {
			for (EditableTransitLine transitLine : sourceScenario.getTransitSchedule().getEditableTransitLines().values()) {
				transitLine.setRealId(transitLine.getId());
				for (EditableTransitRoute transitRoute : transitLine.getEditableRoutes().values()) {
					transitRoute.setRealId(transitRoute.getId());
				}
			}
			for (TransitStopFacility transitStopFacility : sourceScenario.getTransitSchedule().getFacilities().values()) {
				((EditableTransitStopFacility) transitStopFacility).setOrigId(transitStopFacility.getId());
			}
		}
	}

	private EditableScenario readScenario() {
		Config config = ConfigUtils.createConfig();
		if (schedule != null) {
			config.transit().setUseTransit(true);
		}
		EditableScenario tempScenario = EditableScenarioUtils.createScenario(config);
		MatsimNetworkReader reader = new MatsimNetworkReader(tempScenario.getNetwork());
		reader.readFile(network.getAbsolutePath());
		if (schedule != null) {
			new TransitScheduleReader(tempScenario).readFile(schedule.getAbsolutePath());
		}
		return tempScenario;
	}

	private void convertNetwork() {
		for (Node node : sourceScenario.getNetwork().getNodes().values()) {
			EastNorth eastNorth = new EastNorth(node.getCoord().getX(), node.getCoord().getY());
			LatLon latLon = Main.getProjection().eastNorth2latlon(eastNorth);
			org.openstreetmap.josm.data.osm.Node nodeOsm = new org.openstreetmap.josm.data.osm.Node(latLon);

			// set id of MATSim node as tag, as actual id of new MATSim node is
			// set as corresponding OSM node id
			nodeOsm.put(NodeConversionRules.ID, ((NodeImpl) node).getOrigId());
			node2OsmNode.put(node, nodeOsm);
			dataSet.addPrimitive(nodeOsm);
			Node newNode = targetScenario.getNetwork().getFactory().createNode(Id.create(nodeOsm.getUniqueId(), Node.class), node.getCoord());
			((NodeImpl) newNode).setOrigId(((NodeImpl) node).getOrigId());
			targetScenario.getNetwork().addNode(newNode);
		}

		for (Link link : sourceScenario.getNetwork().getLinks().values()) {
			Way way = new Way();
			org.openstreetmap.josm.data.osm.Node fromNode = node2OsmNode.get(link.getFromNode());
			way.addNode(fromNode);
			org.openstreetmap.josm.data.osm.Node toNode = node2OsmNode.get(link.getToNode());
			way.addNode(toNode);
			// set id of link as tag, as actual id of new link is set as
			// corresponding way id
			way.put(LinkConversionRules.ID, ((LinkImpl) link).getOrigId());
			way.put(LinkConversionRules.FREESPEED, String.valueOf(link.getFreespeed()));
			way.put(LinkConversionRules.CAPACITY, String.valueOf(link.getCapacity()));
			way.put(LinkConversionRules.LENGTH, String.valueOf(link.getLength()));
			way.put(LinkConversionRules.PERMLANES, String.valueOf(link.getNumberOfLanes()));
			StringBuilder modes = new StringBuilder();
			for (String mode : link.getAllowedModes()) {
				modes.append(mode);
				if (link.getAllowedModes().size() > 1) {
					// multiple values are separated by ";"
					modes.append(";");
				}
			}
			way.put(LinkConversionRules.MODES, modes.toString());

			dataSet.addPrimitive(way);
			Link newLink = targetScenario
					.getNetwork()
					.getFactory()
					.createLink(Id.create(way.getUniqueId() + "_0", Link.class),
							targetScenario.getNetwork().getNodes().get(Id.create(fromNode.getUniqueId(), Node.class)),
							targetScenario.getNetwork().getNodes().get(Id.create(toNode.getUniqueId(), Node.class)));
			newLink.setFreespeed(link.getFreespeed());
			newLink.setCapacity(link.getCapacity());
			newLink.setLength(link.getLength());
			newLink.setNumberOfLanes(link.getNumberOfLanes());
			newLink.setAllowedModes(link.getAllowedModes());
			((LinkImpl) newLink).setOrigId(((LinkImpl) link).getOrigId());
			targetScenario.getNetwork().addLink(newLink);
			way2Links.put(way, Collections.singletonList(newLink));
			linkId2Way.put(link.getId(), way);
			link2Segment.put(newLink, Collections.singletonList(new WaySegment(way, 0)));
		}
	}

	private void convertStops() {
		for (TransitStopFacility stop : sourceScenario.getTransitSchedule().getFacilities().values()) {
			EastNorth eastNorth = new EastNorth(stop.getCoord().getX(), stop.getCoord().getY());
			LatLon latLon = Main.getProjection().eastNorth2latlon(eastNorth);
			org.openstreetmap.josm.data.osm.Node platform = new org.openstreetmap.josm.data.osm.Node(latLon);
			platform.put("public_transport", "platform");
			if (stop.getName() != null) {
				platform.put("name", stop.getName());
			}
			dataSet.addPrimitive(platform);
			Id<Node> nodeId = null;
			Id<Link> linkId = null;
			Relation relation = new Relation();
			relation.put("type", "public_transport");
			relation.put("public_transport", "stop_area");
			if (stop.getName() != null) {
				relation.put("name", stop.getName());
			}
			relation.put("ref", stop.getId().toString());
			relation.addMember(new RelationMember("platform", platform));
			if (stop.getLinkId() != null) {
				Way newWay = linkId2Way.get(stop.getLinkId());
				if (newWay == null) {
					throw new RuntimeException(stop.getLinkId().toString());
				}
				List<Link> newWayLinks = way2Links.get(newWay);
				Link singleLink = newWayLinks.get(0);
				linkId = Id.createLinkId(singleLink.getId());
				relation.addMember(new RelationMember("matsim:link", newWay));
			}
			if (((EditableTransitStopFacility) stop).getNodeId() != null) {
				org.openstreetmap.josm.data.osm.Node node = node2OsmNode.get(sourceScenario.getNetwork().getNodes().get(((EditableTransitStopFacility) stop).getNodeId()));
				if (node != null) {
					relation.addMember(new RelationMember("stop", node));
					nodeId = Id.createNodeId(node.getUniqueId());
				}
			}
			dataSet.addPrimitive(relation);
			EditableTransitStopFacility newStop = ((EditableTransitStopFacility) targetScenario
					.getTransitSchedule()
					.getFactory()
					.createTransitStopFacility(Id.create(relation.getUniqueId(), TransitStopFacility.class), stop.getCoord(),
							stop.getIsBlockingLane()));
			newStop.setName(stop.getName());
			newStop.setOrigId(((EditableTransitStopFacility) stop).getOrigId());
			newStop.setLinkId(linkId);
			newStop.setNodeId(nodeId);
			targetScenario.getTransitSchedule().addStopFacility(newStop);
			stops.put(stop.getId(), newStop);
			stopRelation2TransitStop.put(relation, newStop);
		}
	}

	private void convertLines() {
		for (EditableTransitLine line : sourceScenario.getTransitSchedule().getEditableTransitLines().values()) {
			Relation lineRelation = new Relation();
			lineRelation.put("type", "route_master");
			lineRelation.put("ref", line.getRealId().toString());
			EditableTransitLine newLine = new EditableTransitLine(Id.create(lineRelation.getUniqueId(), TransitLine.class));
			newLine.setRealId(line.getRealId());
			newLine.setName(line.getName());
			for (EditableTransitRoute route : line.getEditableRoutes().values()) {
				Relation routeRelation = new Relation();
				List<TransitRouteStop> newTransitStops = new ArrayList<>();
				for (TransitRouteStop tRStop : route.getStops()) {
					TransitStopFacility stop = stops.get(tRStop.getStopFacility().getId());
					TransitRouteStop newTRStop = targetScenario.getTransitSchedule().getFactory()
							.createTransitRouteStop(stop, tRStop.getArrivalOffset(), tRStop.getDepartureOffset());
					targetScenario
							.getTransitSchedule()
							.getTransitStopsAttributes()
							.putAttribute(tRStop.getStopFacility().getName() + "_" + route.getId(), "awaitDepartureTime",
									String.valueOf(tRStop.isAwaitDepartureTime()));
					newTransitStops.add(newTRStop);
					Relation stopArea = (Relation) dataSet.getPrimitiveById(Long.parseLong(stop.getId().toString()), OsmPrimitiveType.RELATION);
					OsmPrimitive platform = null;
					for (RelationMember member : stopArea.getMembers()) {
						if (member.hasRole("platform")) {
							platform = member.getMember();
						}
					}
					if (platform != null) {
						routeRelation.addMember(new RelationMember("platform", platform));
					}
				}
				List<Id<Link>> links = new ArrayList<>();
				NetworkRoute networkRoute = route.getRoute();
				NetworkRoute newNetworkRoute;
				if (networkRoute != null) {
					Id<Link> oldStartId = networkRoute.getStartLinkId();
					Way newStartWay = linkId2Way.get(oldStartId);
					List<Link> newStartLinks = way2Links.get(newStartWay);
					Id<Link> startId = newStartLinks.get(0).getId();
					routeRelation.addMember(new RelationMember("", linkId2Way.get(networkRoute.getStartLinkId())));
					for (Id<Link> linkId : networkRoute.getLinkIds()) {
						links.add(way2Links.get(linkId2Way.get(linkId)).get(0).getId());
						routeRelation.addMember(new RelationMember("", linkId2Way.get(linkId)));
					}
					Id<Link> oldEndId = networkRoute.getEndLinkId();
					Link oldEndLink = sourceScenario.getNetwork().getLinks().get(oldEndId);
					Way newEndWay = linkId2Way.get(oldEndLink.getId());
					List<Link> newEndLinks = way2Links.get(newEndWay);
					Id<Link> endId = newEndLinks.get(0).getId();
					routeRelation.addMember(new RelationMember("", linkId2Way.get(networkRoute.getEndLinkId())));
					newNetworkRoute = new LinkNetworkRouteImpl(startId, endId);
					newNetworkRoute.setLinkIds(startId, links, endId);
				} else {
					newNetworkRoute = null;
				}
				EditableTransitRoute newRoute = new EditableTransitRoute(Id.create(routeRelation.getUniqueId(), TransitRoute.class));
				newRoute.setRealId(route.getRealId());
				newRoute.setTransportMode(route.getTransportMode());
				newRoute.getStops().addAll(newTransitStops);
				newRoute.setRoute(newNetworkRoute);
				for (Departure departure : route.getDepartures().values()) {
					newRoute.addDeparture(departure);
				}
				newLine.addRoute(newRoute);
				routeRelation.put("type", "route");
				routeRelation.put("route", route.getTransportMode());
				routeRelation.put("ref", route.getRealId().toString());
				dataSet.addPrimitive(routeRelation);
				lineRelation.addMember(new RelationMember(null, routeRelation));
			}
			dataSet.addPrimitive(lineRelation);
			targetScenario.getTransitSchedule().addTransitLine(newLine);
		}
	}

	public MATSimLayer getLayer() {
		return layer;
	}

}
