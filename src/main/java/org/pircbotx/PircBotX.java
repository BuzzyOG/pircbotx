/**
 * Copyright (C) 2010-2013 Leon Blakey <lord.quackstar at gmail.com>
 *
 * This file is part of PircBotX.
 *
 * PircBotX is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PircBotX is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PircBotX. If not, see <http://www.gnu.org/licenses/>.
 */
package org.pircbotx;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.pircbotx.cap.CapHandler;
import org.pircbotx.cap.EnableCapHandler;
import org.pircbotx.cap.TLSCapHandler;
import org.pircbotx.dcc.Chat;
import org.pircbotx.dcc.SendChat;
import org.pircbotx.dcc.DccHandler;
import org.pircbotx.dcc.SendFileTransfer;
import org.pircbotx.exception.IrcException;
import org.pircbotx.exception.NickAlreadyInUseException;
import org.pircbotx.hooks.CoreHooks;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.*;
import org.pircbotx.hooks.managers.ThreadedListenerManager;
import org.pircbotx.output.OutputCAP;
import org.pircbotx.output.OutputChannel;
import org.pircbotx.output.OutputIRC;
import org.pircbotx.output.OutputRaw;
import org.pircbotx.output.OutputUser;

/**
 * PircBotX is a Java framework for writing IRC bots quickly and easily.
 * <p>
 * It provides an event-driven architecture to handle common IRC
 * events, flood protection, DCC support, ident support, and more.
 * The comprehensive logfile format is suitable for use with pisg to generate
 * channel statistics.
 * <p>
 * Methods of the PircBotX class can be called to send events to the IRC server
 * that it connects to. For example, calling the sendMessage method will
 * send a message to a channel or user on the IRC server. Multiple servers
 * can be supported using multiple instances of PircBotX.
 * <p>
 * To perform an action when the PircBotX receives a normal message from the IRC
 * server, you would listen for the MessageEvent in your listener (see {@link ListenerAdapter}).
 * Many other events are dispatched as well for other incoming lines
 *
 * @author Origionally by:
 * <a href="http://www.jibble.org/">Paul James Mutton</a> for <a href="http://www.jibble.org/pircbot.php">PircBot</a>
 * <p>Forked and Maintained by Leon Blakey <lord.quackstar at gmail.com> in <a href="http://pircbotx.googlecode.com">PircBotX</a>
 */
@RequiredArgsConstructor
@Slf4j
public class PircBotX {
	/**
	 * The definitive version number of this release of PircBotX.
	 */
	//THIS LINE IS AUTOGENERATED, DO NOT EDIT
	public static final String VERSION = "2.0-SNAPSHOT";
	protected static final AtomicInteger botCount = new AtomicInteger();
	//Utility objects
	@Getter
	protected final Configuration configuration;
	@Getter
	protected final InputParser inputParser;
	@Getter
	protected final UserChannelDao userChannelDao;
	@Getter
	protected final DccHandler dccHandler;
	protected final ServerInfo serverInfo;
	//Connection stuff.
	protected Socket socket;
	protected Thread inputParserThread;
	//Writers
	protected final OutputRaw sendRaw;
	protected final OutputIRC sendIRC;
	protected final OutputCAP sendCAP;
	@Getter
	protected List<String> enabledCapabilities = new ArrayList();
	protected String nick = "";
	protected boolean loggedIn = false;
	protected Thread shutdownHook;
	@Getter
	@Setter
	protected boolean autoReconnect;
	@Getter
	@Setter
	protected boolean autoReconnectChannels;
	protected boolean shutdownCalled;
	protected final Object shutdownCalledLock = new Object();

	/**
	 * Constructs a PircBotX with the default settings and 
	 * <ul><li>Add {@link CoreHooks} to the default ListenerManager, {@link ThreadedListenerManager}</li>
	 * <li>Add a shutdown hook (See {@link #useShutdownHook(boolean) })</li>
	 * <li>Add an {@link EnableCapHandler} to enable multi-prefix, ignoring it if it fails</li>
	 * <li>Set {@link #getSocketTimeout() default socket timeout} to 5 minutes</li>
	 * <li>Set {@link #getMessageDelay() message delay} to 1 second</li>
	 * <li>Turn off {@link #isAutoNickChange() auto nick changing}</li>
	 * <li>Turn off {@link #isVerbose() verbose} logging</li>
	 * <li>Turn off {@link #isCapEnabled() () CAP handling}</li>
	 * </ul>
	 */
	public PircBotX(Configuration configuration) {
		botCount.getAndIncrement();
		this.configuration = configuration;
		this.userChannelDao = configuration.getBotFactory().createUserChannelDao(this);
		this.serverInfo = configuration.getBotFactory().createServerInfo(this);
		this.dccHandler = configuration.getBotFactory().createDccHandler(this);
		this.inputParser = configuration.getBotFactory().createInputParser(this);
		this.sendRaw = configuration.getBotFactory().createOutputRaw(this);
		this.sendIRC = configuration.getBotFactory().createOutputIRC(this);
		this.sendCAP = configuration.getBotFactory().createOutputCAP(this);
	}

	/**
	 * Attempt to connect to the specified IRC server using the supplied
	 * port number, password, and socketFactory. On success a {@link ConnectEvent}
	 * will be dispatched
	 *
	 * @param hostname The hostname of the server to connect to.
	 * @param port The port number to connect to on the server.
	 * @param password The password to use to join the server.
	 * @param socketFactory The factory to use for creating sockets, including secure sockets
	 *
	 * @throws IOException if it was not possible to connect to the server.
	 * @throws IrcException if the server would not let us join it.
	 * @throws NickAlreadyInUseException if our nick is already in use on the server.
	 */
	public void connect() throws IOException, IrcException, NickAlreadyInUseException {
		try {
			if (isConnected())
				throw new IrcException("The PircBotX is already connected to an IRC server.  Disconnect first.");
			synchronized (shutdownCalledLock) {
				if (shutdownCalled)
					throw new RuntimeException("Shutdown has not been called but your still connected. This shouldn't happen");
				shutdownCalled = false;
			}
			if (configuration.isUseIdentServer() && IdentServer.getServer() == null)
				throw new RuntimeException("UseIdentServer is enabled but no IdentServer has been started");

			//Reset capabilities
			enabledCapabilities = new ArrayList();

			// Connect to the server by DNS server
			for (InetAddress curAddress : InetAddress.getAllByName(configuration.getServerHostname())) {
				log.debug("Trying address " + curAddress);
				try {
					socket = configuration.getSocketFactory().createSocket(curAddress, configuration.getServerPort(), configuration.getLocalAddress(), 0);

					//No exception, assume successful
					break;
				} catch (Exception e) {
					log.debug("Unable to connect to " + configuration.getServerHostname() + " using the IP address " + curAddress.getHostAddress() + ", trying to check another address.", e);
				}
			}

			//Make sure were connected
			if (socket == null || (socket != null && !socket.isConnected()))
				throw new IOException("Unable to connect to the IRC network " + configuration.getServerHostname() + " (last connection attempt exception attached)");
			log.info("Connected to server.");

			InputStreamReader inputStreamReader = new InputStreamReader(socket.getInputStream(), configuration.getEncoding());
			sendRaw().init(socket);
			BufferedReader breader = new BufferedReader(inputStreamReader);
			configuration.getListenerManager().dispatchEvent(new SocketConnectEvent(this));

			if (configuration.isUseIdentServer())
				IdentServer.getServer().addIdentEntry(socket.getInetAddress(), socket.getPort(), socket.getLocalPort(), configuration.getLogin());

			if (configuration.isCapEnabled())
				// Attempt to initiate a CAP transaction.
				sendCAP().getSupported();

			// Attempt to join the server.
			if (configuration.isWebIrcEnabled())
				sendRaw().rawLineNow("WEBIRC " + configuration.getWebIrcPassword()
						+ " " + configuration.getWebIrcUsername()
						+ " " + configuration.getWebIrcHostname()
						+ " " + configuration.getWebIrcAddress().getHostAddress());
			if (!StringUtils.isBlank(configuration.getServerPassword()))
				sendRaw().rawLineNow("PASS " + configuration.getServerPassword());
			String tempNick = configuration.getName();

			sendRaw().rawLineNow("NICK " + tempNick);
			sendRaw().rawLineNow("USER " + configuration.getLogin() + " 8 * :" + configuration.getVersion());

			// Read stuff back from the server to see if we connected.
			String line;
			int tries = 1;
			boolean capEndSent = false;
			while ((line = breader.readLine()) != null) {
				inputParser.handleLine(line);

				List<String> params = Utils.tokenizeLine(line);
				if (params.size() >= 2) {
					String sender = "";
					if (params.get(0).startsWith(":"))
						sender = params.remove(0);

					String code = params.remove(0);

					//Check for both a successful connection. Inital connection (001-4), user stats (251-5), or MOTD (375-6)
					String[] codes = {"001", "002", "003", "004", "005", "251", "252", "253", "254", "255", "375", "376"};
					if (Arrays.asList(codes).contains(code))
						// We're connected to the server.
						break;
					else if (code.equals("433"))
						//EXAMPLE: AnAlreadyUsedName :Nickname already in use
						//Nickname in use, rename
						if (configuration.isAutoNickChange()) {
							tries++;
							tempNick = configuration.getName() + tries;
							sendRaw().rawLineNow("NICK " + tempNick);
						} else
							throw new NickAlreadyInUseException(line);
					else if (code.equals("439")) {
						//EXAMPLE: PircBotX: Target change too fast. Please wait 104 seconds
						// No action required.
					} else if (configuration.isCapEnabled() && code.equals("451") && params.get(0).equals("CAP")) {
						//EXAMPLE: 451 CAP :You have not registered
						//Ignore, this is from servers that don't support CAP
					} else if (code.startsWith("5") || code.startsWith("4"))
						throw new IrcException("Could not log into the IRC server: " + line);
					else if (code.equals("670")) {
						//Server is saying that we can upgrade to TLS
						SSLSocketFactory sslSocketFactory = ((SSLSocketFactory) SSLSocketFactory.getDefault());
						for (CapHandler curCapHandler : configuration.getCapHandlers())
							if (curCapHandler instanceof TLSCapHandler)
								sslSocketFactory = ((TLSCapHandler) curCapHandler).getSslSocketFactory();
						SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(
								socket,
								socket.getInetAddress().getHostName(),
								socket.getPort(),
								true);
						sslSocket.startHandshake();
						breader = new BufferedReader(new InputStreamReader(sslSocket.getInputStream(), configuration.getEncoding()));
						sendRaw().init(sslSocket);
						socket = sslSocket;
						//Notify CAP Handlers
						for (CapHandler curCapHandler : configuration.getCapHandlers())
							curCapHandler.handleUnknown(this, line);
					} else if (code.equals("CAP")) {
						//Handle CAP Code; remove extra from params
						List<String> capParams = Arrays.asList(params.get(2).split(" "));
						if (params.get(1).equals("LS"))
							for (CapHandler curCapHandler : configuration.getCapHandlers())
								curCapHandler.handleLS(this, capParams);
						else if (params.get(1).equals("ACK")) {
							//Server is enabling a capability, store that
							getEnabledCapabilities().addAll(capParams);

							for (CapHandler curCapHandler : configuration.getCapHandlers())
								curCapHandler.handleACK(this, capParams);
						} else if (params.get(1).equals("NAK"))
							for (CapHandler curCapHandler : configuration.getCapHandlers())
								curCapHandler.handleNAK(this, capParams);
						else
							//Maybe the CapHandlers know how to use it
							for (CapHandler curCapHandler : configuration.getCapHandlers())
								curCapHandler.handleUnknown(this, line);
					} else
						//Pass to CapHandlers, could be important
						for (CapHandler curCapHandler : configuration.getCapHandlers())
							curCapHandler.handleUnknown(this, line);
				}
				//Send CAP END if all CapHandlers are finished
				if (configuration.isCapEnabled() && !capEndSent) {
					boolean allDone = true;
					for (CapHandler curCapHandler : configuration.getCapHandlers())
						if (!curCapHandler.isDone()) {
							allDone = false;
							break;
						}
					if (allDone) {
						sendCAP().end();
						capEndSent = true;

						//Make capabilities unmodifiable for the future
						enabledCapabilities = Collections.unmodifiableList(enabledCapabilities);
					}
				}
			}

			this.nick = tempNick;
			loggedIn = true;
			log.info("Logged onto server.");

			// This makes the socket timeout on read operations after 5 minutes.
			socket.setSoTimeout(configuration.getSocketTimeout());

			if (configuration.isShutdownHookEnabled()) {
				//Add a shutdown hook, using weakreference so PircBotX can be GC'd
				final WeakReference<PircBotX> thisBotRef = new WeakReference(this);
				Runtime.getRuntime().addShutdownHook(shutdownHook = new Thread() {
					@Override
					public void run() {
						PircBotX thisBot = thisBotRef.get();
						if (thisBot != null && thisBot.isConnected() && thisBot.socket != null && !thisBot.socket.isClosed())
							try {
								thisBot.sendIRC().quitServer();
							} finally {
								thisBot.shutdown(true);
							}
					}
				});
				shutdownHook.setName("bot" + botCount + "-shutdownhook");
			}

			//Start input to start accepting lines
			startInputParser(inputParser, breader);

			configuration.getListenerManager().dispatchEvent(new ConnectEvent(this));

			for (Map.Entry<String, String> channelEntry : configuration.getAutoJoinChannels().entrySet())
				sendIRC().joinChannel(channelEntry.getKey(), channelEntry.getValue());
		} catch (Exception e) {
			//if (!(e instanceof IrcException) && !(e instanceof NickAlreadyInUseException))
			//	shutdown(true);
			throw new IOException("Can't connect to server", e);
		}
	}

	protected void startInputParser(final InputParser parser, final BufferedReader inputReader) {
		inputParserThread = new Thread() {
			@Override
			public void run() {
				parser.startLineProcessing(inputReader);
			}
		};
		inputParserThread.start();
	}

	/**
	 * Reconnects to the IRC server that we were previously connected to using 
	 * the same {@link Configuration}
	 * This method will throw an IrcException if we have never connected
	 * to an IRC server previously.
	 *
	 * @since PircBot 0.9.9
	 *
	 * @throws IOException if it was not possible to connect to the server.
	 * @throws IrcException if the server would not let us join it.
	 * @throws NickAlreadyInUseException if our nick is already in use on the server.
	 */
	public synchronized void reconnect() throws IOException, IrcException, NickAlreadyInUseException {
		if (configuration == null)
			throw new IrcException("Cannot reconnect to an IRC server because we were never connected to one previously!");
		try {
			connect();
		} catch (IOException e) {
			configuration.getListenerManager().dispatchEvent(new ReconnectEvent(this, false, e));
			throw e;
		} catch (IrcException e) {
			configuration.getListenerManager().dispatchEvent(new ReconnectEvent(this, false, e));
			throw e;
		} catch (RuntimeException e) {
			configuration.getListenerManager().dispatchEvent(new ReconnectEvent(this, false, e));
			throw e;
		}
		//Should be good
		configuration.getListenerManager().dispatchEvent(new ReconnectEvent(this, true, null));
	}

	public OutputRaw sendRaw() {
		return sendRaw;
	}

	public OutputIRC sendIRC() {
		return sendIRC;
	}

	public OutputCAP sendCAP() {
		return sendCAP;
	}

	/**
	 * Utility method to send a file to a user. Simply calls 
	 * {@link DccHandler#sendFile(java.io.File, org.pircbotx.User, int) }
	 * 
	 * @return When the transfer is finished returns the {@link SendFileTransfer} used
	 * @see DccHandler#sendFile(java.io.File, org.pircbotx.User, int) 
	 * @see DccHandler#sendFileRequest(java.lang.String, org.pircbotx.User, int) 
	 */
	public SendFileTransfer dccSendFile(File file, User reciever, int timeout) throws IOException {
		return dccHandler.sendFile(file, reciever, timeout);
	}

	/**
	 * Utility method to send a chat request to a user. Simply calls
	 * {@link DccHandler#sendChatRequest(org.pircbotx.User) }
	 * 
	 * @return An open {@link Chat}
	 * @see DccHandler#sendChatRequest(org.pircbotx.User) 
	 * @see Chat
	 */
	public SendChat dccSendChatRequest(User sender, int timeout) throws IOException, SocketTimeoutException {
		return dccHandler.sendChatRequest(sender);
	}

	/**
	 * Sets the internal nick of the bot. This is only to be called by the
	 * PircBotX class in response to notification of nick changes that apply
	 * to us.
	 *
	 * @param nick The new nick.
	 */
	protected void setNick(String nick) {
		synchronized (userChannelDao.accessLock) {
			User user = userChannelDao.getUser(this.nick);
			userChannelDao.renameUser(user, nick);
			this.nick = nick;
		}
	}

	/**
	 * Returns the current nick of the bot. Note that if you have just changed
	 * your nick, this method will still return the old nick until confirmation
	 * of the nick change is received from the server.
	 *
	 * @since PircBot 1.0.0
	 *
	 * @return The current nick of the bot.
	 */
	public String getNick() {
		return nick;
	}

	/**
	 * Returns whether or not the PircBotX is currently connected to a server.
	 * The result of this method should only act as a rough guide,
	 * as the result may not be valid by the time you act upon it.
	 *
	 * @return True if and only if the PircBotX is currently connected to a server.
	 */
	public boolean isConnected() {
		return socket != null && !socket.isClosed();
	}

	/**
	 * Returns a String representation of this object.
	 * You may find this useful for debugging purposes, particularly
	 * if you are using more than one PircBotX instance to achieve
	 * multiple server connectivity. The format of
	 * this String may change between different versions of PircBotX
	 * but is currently something of the form
	 * <code>
	 *   Version{PircBotX x.y.z Java IRC Bot - www.jibble.org}
	 *   Connected{true}
	 *   Server{irc.dal.net}
	 *   Port{6667}
	 *   Password{}
	 * </code>
	 *
	 * @since PircBot 0.9.10
	 *
	 * @return a String representation of this object.
	 */
	@Override
	public String toString() {
		return "Version{" + configuration.getVersion() + "}"
				+ " Connected{" + isConnected() + "}"
				+ " Server{" + configuration.getServerHostname() + "}"
				+ " Port{" + configuration.getServerPort() + "}"
				+ " Password{" + configuration.getServerPassword() + "}";
	}

	/**
	 * Gets the bots own user object
	 * @return The user object representing this bot
	 */
	public User getUserBot() {
		return userChannelDao.getUser(getNick());
	}

	/**
	 * @return the serverInfo
	 */
	public ServerInfo getServerInfo() {
		return serverInfo;
	}

	/**
	 * Calls shutdown allowing reconnect
	 */
	public void shutdown() {
		shutdown(false);
	}

	/**
	 * Fully shutdown the bot and all internal resources. This will close the
	 * connections to the server, kill background threads, clear server specific
	 * state, and dispatch a DisconnectedEvent
	 * <p/>
	 * @param noReconnect Toggle whether to reconnect if enabled. Set to true to
	 * 100% shutdown the bot
	 */
	public void shutdown(boolean noReconnect) {
		//Guard against multiple calls
		if (shutdownCalled)
			synchronized (shutdownCalledLock) {
				if (shutdownCalled)
					throw new RuntimeException("Shutdown has already been called");
			}

		try {
			if (inputParserThread != null)
				inputParserThread.interrupt();
		} catch (Exception e) {
			log.error("Cannot interrupt inputThread", e);
		}

		//Close the socket from here and let the threads die
		if (socket != null && !socket.isClosed())
			try {
				socket.close();
			} catch (Exception e) {
				log.error("Cannot close socket", e);
			}

		//Cache channels for possible next reconnect
		Map<String, String> previousChannels = new HashMap();
		for (Channel curChannel : userChannelDao.getAllChannels()) {
			String key = (curChannel.getChannelKey() == null) ? "" : curChannel.getChannelKey();
			previousChannels.put(curChannel.getName(), key);
		}

		//Dispatch event
		if (autoReconnect && !noReconnect)
			try {
				reconnect();
				if (autoReconnectChannels)
					for (Map.Entry<String, String> curEntry : previousChannels.entrySet())
						sendIRC().joinChannel(curEntry.getKey(), curEntry.getValue());
			} catch (Exception e) {
				//Not much we can do with it
				throw new RuntimeException("Can't reconnect to server", e);
			}
		else {
			configuration.getListenerManager().dispatchEvent(new DisconnectEvent(this));
			log.debug("Disconnected.");
		}

		//Shutdown listener manager
		configuration.getListenerManager().shutdown(this);

		//Clear relevant variables of information
		userChannelDao.close();
		inputParser.close();
		dccHandler.close();
	}
}
