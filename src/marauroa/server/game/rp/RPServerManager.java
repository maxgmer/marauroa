/* $Id: RPServerManager.java,v 1.3 2007/02/04 12:57:00 arianne_rpg Exp $ */
/***************************************************************************
 *                      (C) Copyright 2003 - Marauroa                      *
 ***************************************************************************
 ***************************************************************************
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *                                                                         *
 ***************************************************************************/
package marauroa.server.game.rp;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import marauroa.common.Configuration;
import marauroa.common.Log4J;
import marauroa.common.game.IRPZone;
import marauroa.common.game.Perception;
import marauroa.common.game.RPAction;
import marauroa.common.game.RPObject;
import marauroa.common.game.RPObjectInvalidException;
import marauroa.common.game.RPObjectNotFoundException;
import marauroa.common.net.MessageS2CPerception;
import marauroa.common.net.MessageS2CTransferREQ;
import marauroa.common.net.TransferContent;
import marauroa.server.createaccount;
import marauroa.server.game.ActionInvalidException;
import marauroa.server.game.Statistics;
import marauroa.server.game.container.ClientState;
import marauroa.server.game.container.PlayerEntry;
import marauroa.server.game.container.PlayerEntryContainer;
import marauroa.server.net.INetworkServerManager;

import org.apache.log4j.Logger;

/**
 * This class is responsible for adding actions to scheduler, and to build and
 * sent perceptions
 */
public class RPServerManager extends Thread {
	/** the logger instance. */
	private static final Logger logger = Log4J.getLogger(RPServerManager.class);

	/** The thread will be running while keepRunning is true */
	private volatile boolean keepRunning;

	/** isFinished is true when the thread has really exited. */
	private volatile boolean isfinished;

	/** The time elapsed between 2 turns. */
	private long turnDuration;

	/** The number of the turn that we are executing now */
	private int turn;

	/** The scheduler needed to organize actions */
	private RPScheduler scheduler;

	/** The ruleProcessor that the scheduler will use to execute the actions */
	private IRPRuleProcessor ruleProcessor;

	/** The place where the objects are stored */
	private RPWorld world;

	private Statistics stats;

	/** The networkServerManager so that we can send perceptions */
	private INetworkServerManager netMan;

	/** The PlayerEntryContainer so that we know where to send perceptions */
	private PlayerEntryContainer playerContainer;

	private List<Integer> playersToRemove;

	private Map<RPObject.ID, List<TransferContent>> contentsToTransfer;

	/**
	 * Constructor
	 * 
	 * @param netMan
	 *            the NetworkServerManager so that we can send message
	 */
	public RPServerManager(INetworkServerManager netMan) throws Exception {
		super("RPServerManager");
		Log4J.startMethod(logger, "RPServerManager");
		try {
			stats = Statistics.getStatistics();
			keepRunning = true;
			isfinished = false;
			
			scheduler = new RPScheduler();
			contentsToTransfer = new HashMap<RPObject.ID, List<TransferContent>>();
			playerContainer = PlayerEntryContainer.getContainer();
			
			playersToRemove = new LinkedList<Integer>();
			this.netMan = netMan;

			Configuration conf = Configuration.getConfiguration();
			Class worldClass = Class.forName(conf.get("rp_RPWorldClass"));
			// call the get() method without parameters to retrieve the singleton instance
			world = (RPWorld) worldClass.getDeclaredMethod("get", new Class[0]).invoke(null, (Object[]) null);
			world.onInit();

			Class ruleProcessorClass = Class.forName(conf.get("rp_RPRuleProcessorClass"));
			// call the get() method without parameters to retrieve the singleton instance
			ruleProcessor = (IRPRuleProcessor) ruleProcessorClass.getDeclaredMethod("get", new Class[0]).invoke(null, (Object[]) null);
			ruleProcessor.setContext(this);

			String duration = conf.get("rp_turnDuration");

			turnDuration = Long.parseLong(duration);
			turn = 0;
		} catch (Exception e) {
			logger.warn("ABORT: Unable to create RPZone, RPRuleProcessor or RPAIManager instances",	e);
			throw e;
		} finally {
			Log4J.finishMethod(logger, "RPServerManager");
		}
	}

	public int getTurn() {
		return turn;
	}

	/** This method finish the thread that run the RPServerManager */
	public void finish() {
		Log4J.startMethod(logger, "finish");
		keepRunning = false;
		
		while (isfinished == false) {
			Thread.yield();
		}

		try {
			world.onFinish();
		} catch (Exception e) {
			logger.error("error while finishing RPServerManager", e);
		}

		Log4J.finishMethod(logger, "finish");
	}

	/** Adds an action for the next turn */
	public void addRPAction(RPAction action) throws ActionInvalidException {
		Log4J.startMethod(logger, "addRPAction");
		try {
			if (logger.isDebugEnabled()) {
				logger.debug("Added action: " + action);
			}

			scheduler.addRPAction(action, ruleProcessor);
		} finally {
			Log4J.finishMethod(logger, "addRPAction");
		}
	}

	/** Returns an object of the world */
	public RPObject getRPObject(RPObject.ID id)
			throws RPObjectNotFoundException {
		Log4J.startMethod(logger, "getRPObject");
		try {
			IRPZone zone = world.getRPZone(id);
			return zone.get(id);
		} finally {
			Log4J.finishMethod(logger, "getRPObject");
		}
	}

	public boolean checkGameVersion(String game, String version) {
		return ruleProcessor.checkGameVersion(game, version);
	}

	public createaccount.Result createAccount(String username, String password,
			String email, RPObject template) {
		return ruleProcessor.createAccount(username, password, email, template);
	}

	private Perception getPlayerPerception(PlayerEntry entry) {
		Perception perception = null;

		RPObject.ID id=entry.object.getID();
		IRPZone zone = world.getRPZone(id);

		if (entry.requestedSync == false) {
			logger.debug("Perception DELTA for player ("+ id + ")");
			perception = zone.getPerception(id, Perception.DELTA);
		} else {
			entry.requestedSync = false;
			logger.debug("Perception SYNC for player ("+ id + ")");
			perception = zone.getPerception(id, Perception.SYNC);
		}

		return perception;
	}

	private void sendPlayerPerception(PlayerEntry entry, Perception perception, RPObject object) {
		if (perception != null) {
			MessageS2CPerception messages2cPerception = new MessageS2CPerception(entry.channel, perception);

			stats.add("Perceptions "+ (perception.type == 0 ? "DELTA" : "SYNC"), 1);

			/* The perception is build of two parts: the general information and the private information
			 *  about our object.
			 *  This private information consists only of attributes that are not visible to every player
			 *  but the owner, because visible attributes are already stored in the perception.
			 */
			RPObject copy = (RPObject) object.clone();

			if (perception.type == Perception.SYNC) {
				copy.clearVisible();
				messages2cPerception.setMyRPObject(copy, null);
			} else {
				RPObject added = new RPObject();
				RPObject deleted = new RPObject();

				try {
					copy.getDifferences(added, deleted);
					added.clearVisible();
					deleted.clearVisible();

					if (added.size() == 0) {
						added = null;
					}

					if (deleted.size() == 0) {
						deleted = null;
					}
				} catch (Exception e) {
					logger.error("Error getting object differences", e);
					logger.error(object);
					logger.error(copy);
					added = null;
					deleted = null;
				}

				messages2cPerception.setMyRPObject(added, deleted);
			}

			messages2cPerception.setClientID(entry.clientid);
			messages2cPerception.setPerceptionTimestamp(entry.getPerceptionTimestamp());

			netMan.sendMessage(messages2cPerception);
		}
	}

	private void buildPerceptions() {
		Log4J.startMethod(logger, "buildPerceptions");

		playersToRemove.clear();

		/** We reset the cache at Perceptions */
		MessageS2CPerception.clearPrecomputedPerception();
		
		for(PlayerEntry entry: playerContainer) {
			try {
				if (entry.state == ClientState.GAME_BEGIN) {
					Perception perception = getPlayerPerception(entry);
					sendPlayerPerception(entry, perception, entry.object);
				}
			} catch (RuntimeException e) {
				logger.error("Removing player(" + entry.clientid + ") because it caused a Exception while contacting it", e);
				playersToRemove.add(entry.clientid);			}
		}

		Log4J.finishMethod(logger, "buildPerceptions");
	}

	/** This method is called when a player is added to the game */
	public boolean onInit(RPObject object) throws RPObjectInvalidException {
		return ruleProcessor.onInit(object);
	}
	
	/** This method is called when connection to client is closed */
	public void onTimeout(RPObject object) throws RPObjectNotFoundException {
		scheduler.clearRPActions(object.getID());
		ruleProcessor.onTimeout(object);
	}

	/** This method is called when a player leaves the game */
	public boolean onExit(RPObject object) throws RPObjectNotFoundException {
		scheduler.clearRPActions(object.getID());
		return ruleProcessor.onExit(object);
	}

	private void deliverTransferContent() {
		synchronized (contentsToTransfer) {
			for (RPObject.ID id : contentsToTransfer.keySet()) {
				List<TransferContent> content = contentsToTransfer.get(id);
				PlayerEntry entry= playerContainer.get(id);

				entry.contentToTransfer = content;

				MessageS2CTransferREQ mes = new MessageS2CTransferREQ(entry.channel, content);
				mes.setClientID(entry.clientid);

				netMan.sendMessage(mes);
			}

			contentsToTransfer.clear();
		}
	}

	/** This method is triggered to send content to the clients */
	public void transferContent(RPObject.ID id, List<TransferContent> content) {
		Log4J.startMethod(logger, "transferContent");
		synchronized (contentsToTransfer) {
			contentsToTransfer.put(id, content);
		}
		Log4J.finishMethod(logger, "transferContent");
	}

	/** This method is triggered to send content to the clients */
	public void transferContent(RPObject.ID id, TransferContent content) {
		List<TransferContent> list = new LinkedList<TransferContent>();
		list.add(content);

		transferContent(id, list);
	}

	@Override
	public void run() {
		try {
			Log4J.startMethod(logger, "run");
			long start = System.nanoTime();
			long stop;
			long delay;
			long timeStart = 0;
			long[] timeEnds = new long[11];

			while (keepRunning) {
				stop = System.nanoTime();
				try {
					logger.info("Turn time elapsed: " + ((stop - start) / 1000)	+ " microsecs");
					delay = turnDuration - ((stop - start) / 1000000);
					if (delay < 0) {
						StringBuilder sb = new StringBuilder();
						for (long timeEnd : timeEnds) {
							sb.append(" " + (timeEnd - timeStart));
						}

						logger.warn("Turn duration overflow by " + (-delay)	+ " ms: " + sb.toString());
					} else if (delay > turnDuration) {
						logger.error("Delay bigger than Turn duration. [delay: "
										+ delay
										+ "] [turnDuration:"
										+ turnDuration + "]");
						delay = 0;
					}

					// only sleep when the turn delay is > 0
					if (delay > 0) {
						Thread.sleep(delay);
					}
				} catch (InterruptedException e) {
				}
				start = System.nanoTime();
				timeStart = System.currentTimeMillis();

				try {
					playerContainer.getLock().requestWriteLock();
					{
						timeEnds[0] = System.currentTimeMillis();

						/** Get actions that players send */
						scheduler.nextTurn();
						timeEnds[1] = System.currentTimeMillis();

						/** Execute them all */
						scheduler.visit(ruleProcessor);
						timeEnds[2] = System.currentTimeMillis();

						/** Compute game RP rules to move to the next turn */
						ruleProcessor.endTurn();
						timeEnds[3] = System.currentTimeMillis();

						/** Send content that is waiting to players */
						deliverTransferContent();
						timeEnds[4] = System.currentTimeMillis();

						/** Tell player what happened */
						buildPerceptions();
						timeEnds[5] = System.currentTimeMillis();

						/** Move zone to the next turn */
						world.nextTurn();
						timeEnds[6] = System.currentTimeMillis();

						/** Remove timeout players */
						/* NOTE: As we use TCP there are not anymore timeout players */
						//notifyTimedoutPlayers(playersToRemove);
						timeEnds[7] = System.currentTimeMillis();

						turn++;

						ruleProcessor.beginTurn();
						timeEnds[8] = System.currentTimeMillis();
					}
				} finally {
					playerContainer.getLock().releaseLock();
					timeEnds[9] = System.currentTimeMillis();
				}

				stats.set("Objects now", world.size());
				timeEnds[10] = System.currentTimeMillis();
			}
		} catch (Throwable e) {
			logger.fatal("Unhandled exception, server will shut down.", e);
		} finally {
			isfinished = true;
			Log4J.finishMethod(logger, "run");
		}
	}
}