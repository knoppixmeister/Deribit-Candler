package deribit.candler;

import java.util.*;
import java.util.concurrent.*;
import org.jfree.data.time.*;
import org.jfree.data.time.ohlc.*;
import org.joda.time.*;
import org.joda.time.format.*;
import com.squareup.moshi.*;
import okhttp3.*;
import okhttp3.internal.ws.*;
import deribit.candler.UserTradeListener.KIND;
import deribit.rest.*;
import deribit.rest.DBRest.*;
import deribit.utils.*;

public class DBCandler {
	private static final String REAL_WS_BASE_URL	=	"wss://deribit.com/ws/api/v2";
	private static final String TESTNET_WS_BASE_URL	=	"wss://testapp.deribit.com/ws/api/v2";

	public RealWebSocket webSocket = null;
	public int OHLC_ITEMS_COUNT = 500;

	public boolean useTestnet = false;

	private final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder().retryOnConnectionFailure(true)
																		.connectTimeout(10, TimeUnit.SECONDS)
																		//.pingInterval(10, TimeUnit.SECONDS)
																		.build();

	private final Moshi MOSHI											=	new Moshi.Builder().build();

	private final JsonAdapter<TradeSubscription> tradesJsonAdapter					=	MOSHI.adapter(TradeSubscription.class);
	private final JsonAdapter<OrderSubscription> orderSubscriptionAdapter			=	MOSHI.adapter(OrderSubscription.class);
	private final JsonAdapter<UserTradeSubscription> userTradeSubscriptionAdapter	=	MOSHI.adapter(UserTradeSubscription.class);

	public final Map<String, Map<Integer, OHLCSeries>> OHLC_SERIES									= new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, ConcurrentSkipListMap<Double, Double>> ORDER_BOOK 	= new ConcurrentHashMap<>();
	private static boolean obFirstTime = true;

	private final List<CandleListener> candleListeners			=	new CopyOnWriteArrayList<>();
	private final List<TradeListener> tradeListeners			=	new CopyOnWriteArrayList<>();
	private final List<UserTradeListener> userTradeListeners	=	new CopyOnWriteArrayList<>();
	private final List<UserOrderListener> userOrderListeners	=	new CopyOnWriteArrayList<>();
	private final List<ConnectedListener> connectedListeners	=	new CopyOnWriteArrayList<>();
	private final List<GetOHLCsListener> getOhlcsListeners		=	new CopyOnWriteArrayList<>();
	private final List<UserAuthListener> userAuthListeners		=	new CopyOnWriteArrayList<>();
	private final List<OrderBookListener> orderBookListeners	=	new CopyOnWriteArrayList<>();

	// private final List<String> channels = new CopyOnWriteArrayList<String>();

	private long reconnectCnt = -1, cmdIdx = 1000;

	private boolean allowReconnect = true;

	private String apiKey, apiSecret;

	private static final int PLACE_ORDER_CMD_ID = 1;
	private static final int CANCEL_ORDER_CMD_ID = 2;
	private static final int GET_OHLCS_CMD_ID = 3;
	private static final int AUTH_CMD_ID = 4;
	private static final int SUB_USER_ORDER_TRADES_CMD_ID = 5;
	private static final int SUB_TRADES_OB_CMD_ID = 6;
	private static final int GET_USER_TRADES_CMD_ID = 7;
	private static final int TRANSFER_TO_SUB_ACC_CMD_ID = 8;

	private boolean userAuthorized = false;

	public DBCandler() {
	}

	public DBCandler(String apiKey, String apiSecret) {
		this.apiKey = apiKey;
		this.apiSecret = apiSecret;
	}

	public DBCandler(String apiKey, String apiSecret, boolean useTestnet) {
		this.apiKey = apiKey;
		this.apiSecret = apiSecret;
		this.useTestnet = useTestnet;
	}

	public boolean getUserAuthorized() {
		return userAuthorized;
	}

	public void setCredentials(String apiKey, String apiSecret) {
		if(apiKey == null || apiKey.isEmpty() || apiSecret == null || apiSecret.isEmpty()) return;

		this.apiKey = apiKey;
		this.apiSecret = apiSecret;

		if(webSocket != null) {
			webSocket.send("{"+
							"	\"jsonrpc\":	\"2.0\","+
							"	\"id\":			"+AUTH_CMD_ID+","+
							"	\"method\":		\"public/auth\","+
							"	\"params\": {"+
							"		\"grant_type\":		\"client_credentials\","+
							//"		\"scope\":			\"mainaccount\","+
							"		\"client_id\":		\""+apiKey+"\","+
							"		\"client_secret\":	\""+apiSecret+"\""+
							"	}"+
							"}");

			for(String instrument : OHLC_SERIES.keySet()) {
				webSocket.send("{"+
								"	\"jsonrpc\":	\"2.0\","+
								"	\"id\":			"+SUB_USER_ORDER_TRADES_CMD_ID+","+
								"	\"method\":		\"private/subscribe\","+
								"	\"params\": {"+
								"		\"channels\": ["+
								"			\"user.trades."+instrument+".raw\","+
								"			\"user.orders."+instrument+".raw\""+
								"		]"+
								"	}"+
								"}");
			}
		}
	}

	public boolean add(String instrument, int interval) {
		if(!OHLC_SERIES.containsKey(instrument.toUpperCase())) {
			ConcurrentHashMap<Integer, OHLCSeries> ohlcs = new ConcurrentHashMap<>();
			OHLC_SERIES.put(instrument.toUpperCase(), ohlcs);
		}
		if(!OHLC_SERIES.get(instrument.toUpperCase()).containsKey(interval)) {
			OHLC_SERIES.get(instrument.toUpperCase()).put(interval, new OHLCSeries(""));
		}

		if(!fetchOHLCs(instrument, interval)) {
			System.out.println("COULD NOT RECEIVE INITIAL CANDLES. EXIT !!!");
			System.exit(0);
		}

		if(webSocket != null) {
			// webSocket.send("");

			// if(!channels.contains("trades."+pair+".raw")) channels.add("trades."+pair+".raw");
		}

		return true;
	}

	private boolean fetchOHLCs(final String instrument, final int interval) {
		System.out.print("DB_CANDLER. START FETCHING INITIAL CANDLES "+instrument+"/"+interval+" .... ");

		OHLC_SERIES.get(instrument.toUpperCase()).get(interval).clear();

		final DateTime SDT = DateTimeFormat.forPattern("dd.MM.yyyy").parseDateTime("01.10.2019");
		final DateTime EDT = new DateTime();

		final String params =	"instrument_name="+instrument.toUpperCase()+
								"&start_timestamp="+SDT.getMillis()+
								"&end_timestamp="+EDT.getMillis()+
								"&resolution="+interval;

		final String OHLCS_URL =	(useTestnet ? DBRest.TESTNET_REST_API_BASE_URL : DBRest.REST_API_BASE_URL)+
									"/api/v2/public/get_tradingview_chart_data?"+params;

		try {
			final Response response = HTTP_CLIENT.newCall(new Request.Builder().url(OHLCS_URL).build()).execute();
			if(response != null) {
				if(response.isSuccessful()) {
					final String json = response.body().string();

					if(json == null || json.isEmpty()) return false;

					final CandlesResponse cr = MOSHI.adapter(CandlesResponse.class).fromJson(json);
					if(cr == null || cr.result == null || !cr.result.status.equalsIgnoreCase("ok")) return false;

					DateTime dt;
					String dts;

					OHLC_SERIES.get(instrument.toUpperCase()).get(interval).setNotify(false);

					for(int key=0; key<cr.result.ticks.size(); key++) {
						dt = new DateTime(cr.result.ticks.get(key));

						dts = dt.toString().replaceAll("T", " ");
						dts = dts.substring(0, dts.lastIndexOf("."));

						OHLC_SERIES.get(instrument.toUpperCase()).get(interval).add(
							new FixedMillisecond(cr.result.ticks.get(key)),
							cr.result.open.get(key),
							cr.result.high.get(key),
							cr.result.low.get(key),
							cr.result.close.get(key),
							cr.result.volume.get(key)
						);
					}

					OHLC_SERIES.get(instrument.toUpperCase()).get(interval).setNotify(false);

					System.out.println("DONE");

					return true;
				}
				else {
					System.err.println(
						"ERROR:\r\n"+
						response.body().string()
					);
				}

				response.close();
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}

		return false;
	}

	public boolean getOHLCs(final String instrument, final int interval) {
		final DateTime SDT = DateTimeFormat.forPattern("dd.MM.yyyy").parseDateTime("01.10.2019");
		final DateTime EDT = new DateTime();

		String body = 	"{"+
						"	\"jsonrpc\":	\"2.0\","+
						"	\"id\":			"+GET_OHLCS_CMD_ID+","+
						"	\"method\":		\"public/get_tradingview_chart_data\","+
						"	\"params\": {"+
						"		\"instrument_name\":	\""+instrument+"\","+
						"		\"start_timestamp\":	"+SDT.getMillis()+","+
						"		\"end_timestamp\":		"+EDT.getMillis()+","+
						"		\"resolution\":			\""+interval+"\""+
						"	}"+
						"}";
		body = body.replaceAll("\r\n", "").replaceAll("\t", "").replaceAll(" ", "");

		// validate data before send
		try {
			MOSHI.adapter(Object.class).fromJson(body);
		}
		catch(Exception e) {
			e.printStackTrace();

			return false;
		}

		// System.out.println(body);

		return webSocket != null && webSocket.send(body);
	}

	public boolean getUserTrades(String instrument, int count) {
		String body =	"{"+
						"	\"jsonrpc\":	\"2.0\","+
						"	\"id\":			"+GET_USER_TRADES_CMD_ID+","+
						"	\"method\":		\"private/get_user_trades_by_instrument\","+
						"	\"params\":	{"+
						"		\"instrument_name\":	\""+instrument+"\","+
						"		\"count\":				"+count+
						"	}"+
						"}";
		body = body.replaceAll("\t", "").replaceAll(" ", "").replaceAll("\r\n", "");

		// System.out.println(body);

		if(webSocket != null && userAuthorized) {
			return webSocket.send(body);
		}

		return false;
	}

	public long getReconnectCnt() {
		return reconnectCnt;
	}

	public void connect() {
		Log.i("DB_CANDLER. CONNECTING ... ");

		reconnectCnt += 1;

		if(webSocket != null) {
			allowReconnect = false;
			webSocket.close(1000, "CLOSE WS BEFORE CONNECT IF ALREADY CREATED CONNECTION");
			webSocket = null;
		}

		if(reconnectCnt > 0) {
			boolean allPairCandlesReceived = true;
			while(true) {
				allPairCandlesReceived = true;

				for(String pair : new TreeSet<>(OHLC_SERIES.keySet())) {
					for(Integer interval : new TreeSet<>(OHLC_SERIES.get(pair.toUpperCase()).keySet())) {
						OHLC_SERIES.get(pair.toUpperCase()).get(interval).clear();

						if(!fetchOHLCs(pair, interval)) allPairCandlesReceived = false;
					}
				}

				if(!allPairCandlesReceived) {
					try {
						System.out.println("NOT ALL INITIAL CANDLE PAIRS RECEIVED AFTER RECONNECT ATTMPT. TRY AGAIN AFTER 5 sec.");

						Thread.sleep(5 * 1000);
					}
					catch(Exception e) {
						e.printStackTrace();
					}
				}
				else break;
			}
		}

		allowReconnect = true;

		webSocket = (RealWebSocket) HTTP_CLIENT.newWebSocket(
			new Request.Builder().url(useTestnet ? TESTNET_WS_BASE_URL : REAL_WS_BASE_URL).build(),
			new WebSocketListener() {
				public void onOpen(final WebSocket socket, final Response response) {
					Log.i("DB_CANDLER. CONNECTING DONE");

					try {
						Log.i("DB_WS_ON_OPEN"+(response != null ? ": "+response.body().string() : ""));
					}
					catch(Exception e) {
						e.printStackTrace();
					}

					socket.send("{"+
								"	\"jsonrpc\":	\"2.0\","+
								"	\"id\":			"+(cmdIdx++)+","+
								"	\"method\":		\"public/set_heartbeat\","+
								"	\"params\": {"+
								"		\"interval\": 10"+
								"	}"+
								"}");

					socket.send("{"+
								"	\"jsonrpc\":	\"2.0\","+
								"	\"id\":			"+(cmdIdx++)+","+
								"	\"method\":		\"public/subscribe\","+
								"	\"params\": {"+
								"		\"channels\": [\"platform_state\"]"+
								"	}"+
								"}");

					/*
					socket.send("{"+
								"	\"jsonrpc\":	\"2.0\","+
								"	\"method\":		\"public/subscribe\","+
								"	\"id\":			"+(cmdIdx++)+","+
								"	\"params\": {"+
								"		\"channels\": [\"perpetual.BTC-PERPETUAL.raw\",\"ticker.BTC-PERPETUAL.raw\"]"+
								"	}"+
								"}");
					*/

					for(String instrument : OHLC_SERIES.keySet()) {
						socket.send("{"+
									"	\"jsonrpc\":	\"2.0\","+
									"	\"method\":		\"public/subscribe\","+
									"	\"id\":			"+SUB_TRADES_OB_CMD_ID+","+
									"	\"params\": {"+
									"		\"channels\": [\"trades."+instrument+".raw\",\"book."+instrument+".raw\"]"+
									"	}"+
									"}");

						// if(!channels.contains("trades."+pair+".raw")) channels.add("trades."+pair+".raw");
					}

					if(apiKey != null && !apiKey.isEmpty() && apiSecret != null && !apiSecret.isEmpty()) {
						socket.send("{"+
									"	\"jsonrpc\":	\"2.0\","+
									"	\"id\":			"+AUTH_CMD_ID+","+
									"	\"method\":		\"public/auth\","+
									"	\"params\": {"+
									"		\"grant_type\":		\"client_credentials\","+
									//"		\"scope\":			\"mainaccount\","+			//  mainaccount | ip:... ... ... ...
									"		\"client_id\":		\""+apiKey+"\","+
									"		\"client_secret\":	\""+apiSecret+"\""+
									"	}"+
									"}");

						for(String instrument : OHLC_SERIES.keySet()) {
							socket.send("{"+
										"	\"jsonrpc\":	\"2.0\","+
										"	\"id\":			"+SUB_USER_ORDER_TRADES_CMD_ID+","+
										"	\"method\":		\"private/subscribe\","+
										"	\"params\": {"+
										"		\"channels\": ["+
										"			\"user.trades."+instrument+".raw\","+
										"			\"user.orders."+instrument+".raw\""+
										"		]"+
										"	}"+
										"}");
						}
					}

					for(ConnectedListener cl : connectedListeners) {
						cl.onConnectedListener();
					}

					response.close();
				}

				public void onClosed(final WebSocket socket, final int code, final String reason) {
					Log.i("DB_WS_CLOSED. CODE: "+code+"; REASON: '"+reason+"'");

					if(allowReconnect) connect();
				}

				public void onFailure(final WebSocket socket, final Throwable t, final Response response) {
					t.printStackTrace();

					if(response != null) {
						try {
							System.out.println(response.body().string());
						}
						catch(Exception e) {
							e.printStackTrace();
						}

						response.close();
					}

					if(allowReconnect) connect();
				}

				public void onMessage(final WebSocket socket, final String msg) {
					parseMessage(msg, socket);
				}
			}
		);
	}

	public void addConnectedListener(ConnectedListener connectedListener) {
		connectedListeners.add(connectedListener);
	}

	public void addCandleListener(CandleListener listener) {
		candleListeners.add(listener);
	}

	public void addTradeListener(TradeListener tradeListener) {
		tradeListeners.add(tradeListener);
	}

	public void addUserOrderListener(UserOrderListener userOrderListener) {
		userOrderListeners.add(userOrderListener);
	}

	public void addUserTradeListener(UserTradeListener userTradeListener) {
		userTradeListeners.add(userTradeListener);
	}

	public void addOhlcsListener(GetOHLCsListener ohlcListener) {
		getOhlcsListeners.add(ohlcListener);
	}

	public void addUserAuthListener(UserAuthListener userAuthListener) {
		userAuthListeners.add(userAuthListener);
	}

	public void addOrderBookListener(OrderBookListener orderBookListener) {
		orderBookListeners.add(orderBookListener);
	}

	public boolean placeOrderAsync(POS_TYPE type, POS_SIDE side, long amount, double price) {
		return placeOrderAsync(type, side, amount, price, false, null, null);
	}

	public boolean placeOrderAsync(POS_TYPE type, POS_SIDE side, long amount, double price, boolean reduceOnly) {
		return placeOrderAsync(type, side, amount, price, reduceOnly, null, null);
	}

	public boolean placeOrderAsync(POS_TYPE type, POS_SIDE side, long amount, double price, boolean reduceOnly, String customId) {
		return placeOrderAsync(type, side, amount, price, reduceOnly, customId, null);
	}

	public boolean placeOrderAsync(POS_TYPE type, POS_SIDE side, long amount, double price, boolean reduceOnly, String customId, Integer opId) {
		if(customId == null || customId.isEmpty()) customId = UUID.randomUUID().toString();

		if(opId == null || opId < 1) opId = PLACE_ORDER_CMD_ID;

		String body	=	"{"+
						"	\"jsonrpc\":	\"2.0\","+
						"	\"id\":			"+(opId)+","+
						"	\"method\":		\"private/"+(side == POS_SIDE.BUY ? "buy" : "sell")+"\","+
						"	\"params\": {"+
						"		\"instrument_name\":	\"BTC-PERPETUAL\","+
						"		\"amount\":				"+amount+","+
						"		\"type\":				\""+(type == POS_TYPE.LIMIT ? "limit" : "market")+"\","+

						(type == POS_TYPE.LIMIT ? "\"price\": "+price+"," : "")+

						"		\"time_in_force\":		\"good_til_cancelled\","+

						(type == POS_TYPE.LIMIT ? "\"post_only\": true," : "")+

						"		\"reduce_only\":		"+reduceOnly+","+
						"		\"label\":				\""+customId+"\""+
						"	}"+
						"}";
		body = body.replaceAll("\r\n", "").replaceAll("\t", "").replaceAll(" ", "");

		// System.out.println(body);

		// validate input data for post 
		try {
			MOSHI.adapter(Object.class).fromJson(body);
		}
		catch(Exception e) {
			e.printStackTrace();

			return false;
		}

		if(webSocket != null) return webSocket.send(body);

		return false;
	}

	public void cancelOrderAsync(String orderId) {
		cancelOrderAsync(orderId, CANCEL_ORDER_CMD_ID);
	}

	public void cancelOrderAsync(String orderId, Integer opId) {
		if(opId == null || opId < 1) opId = CANCEL_ORDER_CMD_ID;

		String body =	"{"+
						"	\"jsonrpc\":	\"2.0\","+
						"	\"id\":			"+opId+","+
						"	\"method\":		\"private/cancel\","+
						"	\"params\": {"+
						"		\"order_id\": \""+orderId+"\""+
						"	}"+
						"}";
		body = body.replaceAll("\r\n", "").replaceAll("\t", "").replaceAll(" ", "");

		// System.out.println(body);

		if(webSocket != null && userAuthorized) webSocket.send(body);
	}

	// dbr.transferToSubaccount(0.00000082, 10)
	public boolean transferToSubaccount(double amount, long destionationAccountId) {
		String body =	"{"+
						"	\"jsonrpc\":	\"2.0\","+
						"	\"id\":			"+TRANSFER_TO_SUB_ACC_CMD_ID+","+
						"	\"method\":		\"private/submit_transfer_to_subaccount\","+
						"	\"params\": {"+
						"		\"currency\":		\"BTC\","+
						"		\"amount\":			"+String.format(Locale.US, "%.8f", amount)+","+
						"		\"destination\":	"+destionationAccountId+
						"	}"+
						"}";
		body = body.replaceAll("\t", "").replaceAll("\r\n", "").replaceAll(" ", "");

		if(webSocket != null && userAuthorized) {
			return webSocket.send(body);
		}

		/*
			{
				"jsonrpc":	"2.0",
				"result":	{
					"updated_timestamp":	1571690,
					"type":					"subaccount",
					"state":				"confirmed",
					"other_side":			"pr...",
					"id":					80,
					"direction":			"payment",
					"currency":				"BTC",
					"created_timestamp":	157169012,
					"amount":				2
				},
				"usIn":		1571690,
				"usOut":	1571690,
				"usDiff":	29,
				"testnet":	false
			}
		 */

		return false;
	}

	private void parseMessage(final String message, final WebSocket socket) {
		/*
		if(
			!message.contains("heartbeat") &&
			!message.contains("version") &&
			!message.contains("\"trades.") &&
			!message.contains("book.BTC-PERPETUAL.raw")
		)
		{
			Log.i("ON_MSG: "+message);
		}
		*/

		UserTradeSubscription uts;
		TradeSubscription ts;
		OrderSubscription os;

		if(
			message.contains("\"method\":\"subscription\"") &&
			message.contains("\"book.BTC-PERPETUAL.raw\"")
		)
		{
			try {
				final OrderBookResponse obr = MOSHI.adapter(OrderBookResponse.class).fromJson(message);
				if(obr == null || obr.params == null || obr.params.data == null) return;

				if(obFirstTime) {
					ORDER_BOOK.put("BIDS", new ConcurrentSkipListMap<Double, Double>());
					ORDER_BOOK.put("ASKS", new ConcurrentSkipListMap<Double, Double>());

					for(List<Object> bd : obr.params.data.bids) {
						if(bd.get(0).equals("new")) {
							ORDER_BOOK.get("BIDS").put(Double.valueOf(bd.get(1)+""), Double.valueOf(bd.get(2)+""));
						}
					}
					for(List<Object> ad : obr.params.data.asks) {
						if(ad.get(0).equals("new")) {
							ORDER_BOOK.get("ASKS").put(Double.valueOf(ad.get(1)+""), Double.valueOf(ad.get(2)+""));
						}
					}

					obFirstTime = false;
				}
				else {
					for(List<Object> bd : obr.params.data.bids) {
						if(bd.get(0).equals("new") || bd.get(0).equals("change")) {
							ORDER_BOOK.get("BIDS").put(Double.valueOf(bd.get(1)+""), Double.valueOf(bd.get(2)+""));
						}
						else if(bd.get(0).equals("delete")) {
							ORDER_BOOK.get("BIDS").remove(Double.valueOf(bd.get(1)+""));
						}
					}
					for(List<Object> ad : obr.params.data.asks) {
						if(ad.get(0).equals("new") || ad.get(0).equals("change")) {
							ORDER_BOOK.get("ASKS").put(Double.valueOf(ad.get(1)+""), Double.valueOf(ad.get(2)+""));
						}
						else if(ad.get(0).equals("delete")) {
							ORDER_BOOK.get("ASKS").remove(Double.valueOf(ad.get(1)+""));
						}
					}

					obFirstTime = false;
				}

				for(OrderBookListener obl : orderBookListeners) {
					obl.onOrderBookChange(ORDER_BOOK);
				}
			}
			catch(Exception e) {
			}

			return;
		}

		if(message.contains("\"id\":"+TRANSFER_TO_SUB_ACC_CMD_ID)) {
			// System.out.println("\r\n"+message+"\r\n");

			return;
		}

		if(message.contains("\"id\":"+GET_USER_TRADES_CMD_ID)) {
			try {
				final UserTradesResponse utr = MOSHI.adapter(UserTradesResponse.class).fromJson(message);
				if(utr == null) return;

				for(UserTrade ut : utr.result.trades) {
					for(UserTradeListener utl : userTradeListeners) {
						utl.onNewTrade(KIND.GET_EVENT, ut, ut.instrument_name, ut.toString());
					}
				}
			}
			catch(Exception e) {
				e.printStackTrace();
			}

			return;
		}

		if(message.contains("\"id\":"+PLACE_ORDER_CMD_ID)) {
			try {
				final SetOrderResponse sor = MOSHI.adapter(SetOrderResponse.class).fromJson(message);
				if(sor == null) return;

				if(sor.error != null) {
					System.out.println("\r\n"+message+"\r\n");
				}
			}
			catch(Exception e) {
				e.printStackTrace();
			}

			return;
		}

		if(message.contains("\"id\":"+AUTH_CMD_ID)) {
			try {
				final UserAuthResponse uar = MOSHI.adapter(UserAuthResponse.class).fromJson(message);
				if(uar == null || uar.error != null || uar.result == null) {
					userAuthorized = false;

					for(UserAuthListener ual : userAuthListeners) {
						ual.onUserAuth(false, null);
					}
				}
				else {
					userAuthorized = true;

					for(UserAuthListener ual : userAuthListeners) {
						ual.onUserAuth(true, uar.result);
					}
				}
			}
			catch(Exception e) {
				e.printStackTrace();

				System.err.println("ERR_MESAGE:\r\n"+message);
			}

			return;
		}

		if(message.contains("\"id\":"+GET_OHLCS_CMD_ID)) {
			try {
				final CandlesResponse cr = MOSHI.adapter(CandlesResponse.class).fromJson(message);
				if(cr == null || cr.error != null || cr.result == null || !cr.result.status.equalsIgnoreCase("ok")) return;

				OHLCSeries _OHLC_SERIES = new OHLCSeries("");

				_OHLC_SERIES.setNotify(false);

				DateTime dt;
				for(int key=0; key<cr.result.ticks.size(); key++) {
					dt = new DateTime(cr.result.ticks.get(key));

					_OHLC_SERIES.add(
						new FixedMillisecond(cr.result.ticks.get(key)),
						cr.result.open.get(key),
						cr.result.high.get(key),
						cr.result.low.get(key),
						cr.result.close.get(key),
						cr.result.volume.get(key)
					);
				}

				_OHLC_SERIES.setNotify(true);

				for(GetOHLCsListener gol : getOhlcsListeners) {
					gol.onOHLCReceived(_OHLC_SERIES);
				}
			}
			catch(Exception e) {
				e.printStackTrace();

				System.err.println("ERR_MESSAGE:\r\n"+message);
			}

			return;
		}

		/*
		{
			"jsonrpc":	"2.0",
			"method":	"subscription",
			"params": {
				"channel": "trades.BTC-PERPETUAL.raw",
				"data": [
					{
						"trade_seq":		25203917,
						"trade_id":			"451",
						"timestamp":		15713,
						"tick_direction":	0,
						"price":			8087.0,
						"instrument_name":	"BTC-PERPETUAL",
						"index_price":		8087.28,
						"direction":		"buy",
						"amount":			130.0
					}
				]
			}
		}
		*/
		if(
			message.contains("subscription") && 
			message.contains("\"channel\":\"trades.")
		)
		{
			DateTime tradeDT;
			try {
				ts = tradesJsonAdapter.fromJson(message);

				if(ts == null || ts.params == null || ts.params.data == null || ts.params.data.size() == 0) return;

				OHLCItem lastCandle;
				long lastCandleEndMs;

				for(Trade t : ts.params.data) {
					for(TradeListener tl : tradeListeners) {
						tl.onNewTrade(t, t.instrument_name, message);
					}

					if(OHLC_SERIES == null || !OHLC_SERIES.containsKey(t.instrument_name)) return;
					boolean isCandleUpdate = true;

					tradeDT = new DateTime(t.timestamp);

					for(Integer intervalKey : OHLC_SERIES.get(t.instrument_name).keySet()) {
						while(true) {
							if(OHLC_SERIES.get(t.instrument_name).get(intervalKey).getItemCount() > OHLC_ITEMS_COUNT) {
								OHLC_SERIES.get(t.instrument_name).get(intervalKey).remove(0);
							}
							else break;
						}
					}

					for(Integer intervalKey : OHLC_SERIES.get(t.instrument_name).keySet()) {
						if(OHLC_SERIES.get(t.instrument_name).get(intervalKey).getItemCount() < 1) continue;

						lastCandle		=	(OHLCItem) OHLC_SERIES.get(t.instrument_name).get(intervalKey).getDataItem(OHLC_SERIES.get(t.instrument_name).get(intervalKey).getItemCount()-1);
						lastCandleEndMs	=	lastCandle.getPeriod().getFirstMillisecond()+(intervalKey*60*1000);

						if(tradeDT.getMillis() > lastCandleEndMs) {
							OHLC_SERIES.get(t.instrument_name).get(intervalKey).add(
								new FixedMillisecond(lastCandleEndMs),
								t.price,
								t.price,
								t.price,
								t.price,
								Utils.round(t.amount/t.price, 8) // translate USD volume to BTC volume
							);

							if(OHLC_SERIES.get(t.instrument_name).get(intervalKey).getItemCount() > OHLC_ITEMS_COUNT) {
								OHLC_SERIES.get(t.instrument_name).get(intervalKey).remove(0);
							}

							isCandleUpdate = false;
						}
						else {// update last candle
							OHLC_SERIES.get(t.instrument_name)
										.get(intervalKey)
										.updatePriceVolume(
											t.price,
											lastCandle.getVolume()+Utils.round(t.amount/t.price, 8) // translate USD volume to BTC volume
										);

							isCandleUpdate = true;
						}

						// notify listeners of candles (OHLCs)
						for(CandleListener cl : candleListeners) {
							cl.onNewCandleData(
								OHLC_SERIES.get(t.instrument_name).get(intervalKey),
								t.instrument_name.toUpperCase(),
								intervalKey,
								isCandleUpdate,
								message
							);
						}
					}
				}
			}
			catch(Exception e) {
				e.printStackTrace();
			}

			return;
		}

		/*
		{
			"jsonrpc":"2.0",
			"method":"subscription",
			"params":{
				"channel":"user.orders.BTC-PERPETUAL.raw",
				"data":{
					"time_in_force":	"good_til_cancelled",
					"replaced":			false,
					"reduce_only":		false,
					"profit_loss":		0.0,
					"price":			8001.0,
					"post_only":		true,
					"order_type":				"limit",
					"order_state":				"open",
					"order_id":					"29710",
					"max_show":					10,
					"last_update_timestamp":	1571,
					"label":					"",
					"is_liquidation":			false,
					"instrument_name":			"BTC-PERPETUAL",
					"filled_amount":			0,
					"direction":				"buy",
					"creation_timestamp":		15712,
					"commission":				0.0,
					"average_price":			0.0,
					"api":						false,
					"amount":					10
				}
			}
		}
		*/
		if(
			message.contains("\"subscription\"") &&
			message.contains("\"user.orders.")
		)
		{
			try {
				os = orderSubscriptionAdapter.fromJson(message);

				if(os == null || os.params == null || os.params.data == null) return;

				for(UserOrderListener uol : userOrderListeners) {
					uol.onNewUserOrder(os.params.data, os.params.data.instrument_name, message);
				}
			}
			catch(Exception e) {
				e.printStackTrace();
			}

			return;
		}

		/*
			{
				"jsonrpc":"2.0",
				"method":"subscription",
				"params":{
					"channel":"user.trades.BTC-PERPETUAL.raw",
					"data":[
						{
							"trade_seq":		13078153,
							"trade_id":"		23881492",
							"timestamp":		1571,
							"tick_direction":	0,
							"state":			"filled",
							"self_trade":		false,
							"reduce_only":		true,
							"price":			8003.5,
							"post_only":		true,
							"order_type":		"limit",
							"order_id":			"29710",
							"matching_id":		null,
							"liquidity":		"M",
							"instrument_name":	"BTC-PERPETUAL",
							"index_price":		7990.15,
							"fee_currency":		"BTC",
							"fee":				-3.1e-7,
							"direction":		"sell",
							"amount":			10.0
						}
					]
				}
			}
		*/
		if(
			message.contains("\"subscription\"") && 
			message.contains("\"user.trades.")
		)
		{
			try {
				uts = userTradeSubscriptionAdapter.fromJson(message);

				if(uts == null) return;

				for(UserTrade ut : uts.params.data) {
					for(UserTradeListener utl : userTradeListeners) {
						utl.onNewTrade(KIND.TRADE_EVENT, ut, ut.instrument_name, message);
					}
				}
			}
			catch(Exception e) {
				e.printStackTrace();
			}

			return;
		}

		/*
			{
			    "jsonrpc": "2.0",
			    "method": "subscription",
			    "params": {
			         "channel": "platform_state",
			         "data": {
			            "locked": true
			        }
			    }
			}
		*/
		if(
			message.contains("subscription") &&
			message.contains("platform_state")
		)
		{
			;

			return;
		}

		if(
			message.contains("heartbeat") &&
			message.contains("test_request")
		)
		{
			socket.send("{"+
						"	\"jsonrpc\":	\"2.0\","+
						"	\"id\":			"+(cmdIdx++)+","+
						"	\"method\":		\"public/test\""+
						"}");
		}
	}
}

/*
{
	"jsonrpc":	"2.0",
	"method":	"subscription",
	"params": {
		"channel": "trades.BTC-PERPETUAL.raw",
		"data": [
			{
				"trade_seq":		13047829,
				"trade_id":			"23848994",
				"timestamp":		1571147647013,
				"tick_direction":	1,
				"price":			8293.0,
				"instrument_name":	"BTC-PERPETUAL",
				"index_price":		8302.86,
				"direction":		"buy",
				"amount":			40.0
			}
		]
	}
}
*/
class BaseResponse {
	public String jsonrpc;
	public long id;
	public Error error;
	public long usIn, usOut, usDiff;
	public boolean testnet;
}

class UserTradesResponse extends BaseResponse {
	public UserTradesResponseResult result;
}

class UserTradesResponseResult {
	public List<UserTrade> trades;
	public boolean has_more;
}

class OrderBookResponse {
	public OrderBookResponseParams params;
}

class OrderBookResponseParams {
	public String channel;
	public OrderBookResponseParamsData data;
}

class OrderBookResponseParamsData {
	public long timestamp;
	public String instrument_name;
	public List<List<Object>> bids;
	public List<List<Object>> asks;
}

class SetOrderResponse extends BaseResponse {
}

/*
	"result":{
		"token_type":		"bearer",
		"scope":			"account:read block_trade:read connection mainaccount trade:read_write wallet:read",
		"refresh_token":	"16029",
		"expires_in":		31536000,
		"access_token":		"1602928534475.1BY1c0Vz."
	}
*/
class UserAuthResponse extends BaseResponse {
	public UserAuthResult result;
}

class OrderSubscription {
	public String jsonrpc, method;
	public OrderSubscriptionParams params;
}

class OrderSubscriptionParams {
	public String channel;
	public Order data;
}

class UserTradeSubscription {
	public String jsonrpc, method;
	public UserTradeSubscriptionParams params;
}

class UserTradeSubscriptionParams {
	public String channel;
	public List<UserTrade> data;
}

class TradeSubscription {
	public String jsonrpc, method;
	public TradeSubscriptionParams params;
}

class TradeSubscriptionParams {
	public String channel;
	public List<Trade> data;
}

// "error":{"message":"Invalid params","data":{"reason":"wrong format","param":"instrument_name"},"code":-32602},"testnet":true,"usIn":1571,"usOut":1571,"usDiff":717}
class CandlesResponse {
	public CandlesResult result;
	public Error error;
	public boolean testnet;
	public long usIn, usOut, usDiff;
}

class CandlesResult {
	public String status;
	public List<Double> volume, open, high, low, close;
	public List<Long> ticks;
}

class Error {
	public String message;
	public ErrorData data;
	public long code;
}

class ErrorData {
	public String reason, param;
}
