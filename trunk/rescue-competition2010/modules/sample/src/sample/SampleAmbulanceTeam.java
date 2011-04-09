package sample;

import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;

import rescuecore2.worldmodel.EntityID;
import rescuecore2.worldmodel.ChangeSet;
import rescuecore2.messages.Command;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.log.Logger;

import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Blockade;
import rescuecore2.standard.entities.Road;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.standard.entities.AmbulanceTeam;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.Civilian;
import rescuecore2.standard.entities.Refuge;
import rescuecore2.standard.messages.AKSpeak;

/**
   A sample ambulance team agent.
 */
public class SampleAmbulanceTeam extends AbstractSampleAgent<AmbulanceTeam> {
    private Collection<EntityID> unexploredBuildings;
    
    private static final String DISTANCE_KEY = "clear.repair.distance";
    private int distance;
    private int hpAnterior = 999999;
    
    @Override
    public String toString() {
        return "Sample ambulance team";
    }

    @Override
    protected void postConnect() {
        super.postConnect();
        model.indexClass(StandardEntityURN.CIVILIAN, StandardEntityURN.FIRE_BRIGADE, StandardEntityURN.POLICE_FORCE, StandardEntityURN.AMBULANCE_TEAM, StandardEntityURN.REFUGE, StandardEntityURN.BUILDING);
        unexploredBuildings = new HashSet<EntityID>(buildingIDs);
        distance = config.getIntValue(DISTANCE_KEY);
    }

    @Override
    protected void think(int time, ChangeSet changed, Collection<Command> heard) {
        if (time == config.getIntValue(kernel.KernelConstants.IGNORE_AGENT_COMMANDS_KEY)) {
            // Subscribe to channel 1
            sendSubscribe(time, 1);
        }
        for (Command next : heard) {
            Logger.debug("Heard " + next);
//            String mensagem = "";
//            if (next instanceof AKSpeak) {
//            	try {
//            		AKSpeak hear = (AKSpeak) next;
//            		mensagem = new String(hear.getContent());
//            	} catch (Exception e) {
//					// TODO: handle exception
//				}	
//            }            
//            System.out.println("\n\nDescrição da mensagem escutada:\n -Time:"+next.getTime()+"\n -Agent ID:"+next.getAgentID()+"\n- Message Urn:"+mensagem+"\n\n");
//        
        }    
                       
        updateUnexploredBuildings(changed);
        
        // issues a rescue message, if it is buried
		if (((Human)me()).getBuriedness()>0) 
		{
			Logger.trace(" - buried: sending rescue message");
			sendRest(time);
			return;
		}
		
		// moves to closest refuge, if it is damaged
		if ((((Human)me()).getDamage()>0) || (((Human)me()).getHP() < 10000))
		{			
			// Am I at a refuge?
            if (location() instanceof Refuge) {
                //Healing
                Logger.info("Healing\n -HP anterior: " + hpAnterior +"\n -HP atual: "+((Human)me()).getHP());
                System.out.println("Healing\n -HP anterior: " + hpAnterior +"\n -HP atual: "+((Human)me()).getHP());
                hpAnterior = ((Human)me()).getHP();
                ((Human)me()).setHP(10000);
                return;
            }			
			// Move to a refuge
            List<EntityID> path = search.breadthFirstSearch(me().getPosition(), refugeIDs);
            if (path != null) {
                Logger.info("Moving to refuge");
                sendMove(time, path);
                return;
            }
            Logger.trace(" - damaged: moving to regufe");		
            return;
		}
        
        
        // Am I near a blockade?
        Blockade target = getTargetBlockade();
        if (target != null) {
            Logger.info("Blockade at: " + target);
            //sendSpeak(time, 1, ("I need Clearing at: " + target.getX() + "-" + target.getY()).getBytes());
            //sendSpeak(time, 1, ("I need Clearing at: " + target.getPosition().getValue()).getBytes());
            //sendSpeak(time, 1, ("I need Clearing at: " + me().getLocation(model)).getBytes());
            sendSpeak(time, 1, ("I need Clearing at: " + location().getID().getValue()).getBytes());
        }
        
        // Am I transporting a civilian to a refuge?
        if (someoneOnBoard()) {
            // Am I at a refuge?
            if (location() instanceof Refuge) {
                // Unload!
                Logger.info("Unloading");
                sendUnload(time);
                return;
            }
            else {
                // Move to a refuge
                List<EntityID> path = search.breadthFirstSearch(me().getPosition(), refugeIDs);
                if (path != null) {
                    Logger.info("Moving to refuge");
                    sendMove(time, path);
                    return;
                }
                // What do I do now? Might as well carry on and see if we can dig someone else out.
                Logger.debug("Failed to plan path to refuge");
            }
        }
        // Go through targets (sorted by distance) and check for things we can do
        for (Human next : getTargets()) {
            if (next.getPosition().equals(location().getID())) {
                // Targets in the same place might need rescueing or loading
                if ((next instanceof Civilian) && next.getBuriedness() == 0 && !(location() instanceof Refuge)) {
                    // Load
                    Logger.info("Loading " + next);
                    sendLoad(time, next.getID());
                    return;
                }
                if (next.getBuriedness() > 0) {
                    // Rescue
                    Logger.info("Rescueing " + next);
                    sendRescue(time, next.getID());
                    return;
                }
            }
            else {
                // Try to move to the target
                List<EntityID> path = search.breadthFirstSearch(me().getPosition(), next.getPosition());
                if (path != null) {
                    Logger.info("Moving to target");
                    sendMove(time, path);
                    return;
                }
            }
        }
        // Nothing to do
        List<EntityID> path = search.breadthFirstSearch(me().getPosition(), unexploredBuildings);
        if (path != null) {
            Logger.info("Searching buildings");
            sendMove(time, path);
            return;
        }
        Logger.info("Moving randomly");
        sendMove(time, randomWalk());
    }

    @Override
    protected EnumSet<StandardEntityURN> getRequestedEntityURNsEnum() {
        return EnumSet.of(StandardEntityURN.AMBULANCE_TEAM);
    }

    private boolean someoneOnBoard() {
        for (StandardEntity next : model.getEntitiesOfType(StandardEntityURN.CIVILIAN)) {
            if (((Human)next).getPosition().equals(getID())) {
                Logger.debug(next + " is on board");
                return true;
            }
        }
        return false;
    }

    private List<Human> getTargets() {
        List<Human> targets = new ArrayList<Human>();
        for (StandardEntity next : model.getEntitiesOfType(StandardEntityURN.CIVILIAN, StandardEntityURN.FIRE_BRIGADE, StandardEntityURN.POLICE_FORCE, StandardEntityURN.AMBULANCE_TEAM)) {
            Human h = (Human)next;
            if (h == me()) {
                continue;
            }
            if (h.isHPDefined()
                && h.isBuriednessDefined()
                && h.isDamageDefined()
                && h.isPositionDefined()
                && h.getHP() > 0
                && (h.getBuriedness() > 0 || h.getDamage() > 0)) {
                targets.add(h);
            }
        }
        Collections.sort(targets, new DistanceSorter(location(), model));
        return targets;
    }

    private void updateUnexploredBuildings(ChangeSet changed) {
        for (EntityID next : changed.getChangedEntities()) {
            unexploredBuildings.remove(next);
        }
    }
    
    private Blockade getTargetBlockade() {
        Logger.debug("Looking for target blockade");
        Area location = (Area)location();
        Logger.debug("Looking in current location");
        Blockade result = getTargetBlockade(location, distance);
        if (result != null) {
            return result;
        }
        Logger.debug("Looking in neighbouring locations");
        for (EntityID next : location.getNeighbours()) {
            location = (Area)model.getEntity(next);
            result = getTargetBlockade(location, distance);
            if (result != null) {
                return result;
            }
        }
        return null;
    }
    
    private Blockade getTargetBlockade(Area area, int maxDistance) {
        //        Logger.debug("Looking for nearest blockade in " + area);
        if (area == null || !area.isBlockadesDefined()) {
            //            Logger.debug("Blockades undefined");
            return null;
        }
        List<EntityID> ids = area.getBlockades();
        // Find the first blockade that is in range.
        int x = me().getX();
        int y = me().getY();
        for (EntityID next : ids) {
            Blockade b = (Blockade)model.getEntity(next);
            double d = findDistanceTo(b, x, y);
            //            Logger.debug("Distance to " + b + " = " + d);
            if (maxDistance < 0 || d < maxDistance) {
                //                Logger.debug("In range");
                return b;
            }
        }
        //        Logger.debug("No blockades in range");
        return null;
    }
     
    private int findDistanceTo(Blockade b, int x, int y) {
        //        Logger.debug("Finding distance to " + b + " from " + x + ", " + y);
        List<Line2D> lines = GeometryTools2D.pointsToLines(GeometryTools2D.vertexArrayToPoints(b.getApexes()), true);
        double best = Double.MAX_VALUE;
        Point2D origin = new Point2D(x, y);
        for (Line2D next : lines) {
            Point2D closest = GeometryTools2D.getClosestPointOnSegment(next, origin);
            double d = GeometryTools2D.getDistance(origin, closest);
            //            Logger.debug("Next line: " + next + ", closest point: " + closest + ", distance: " + d);
            if (d < best) {
                best = d;
                //                Logger.debug("New best distance");
            }

        }
        return (int)best;
    }
    
}
