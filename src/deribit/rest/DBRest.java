package deribit.rest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.jfree.data.time.*;
import org.jfree.data.time.ohlc.*;
import org.joda.time.DateTime;
import org.joda.time.format.*;
import com.squareup.moshi.*;
import candler.*;
import okhttp3.*;

public class DBRest {
	public static final String REST_API_BASE_URL			=	"https://deribit.com";
	public static final String TESTNET_REST_API_BASE_URL	=	"https://testapp.deribit.com";

	public boolean useTestnet = false; 

	public static enum POS_TYPE {
		LIMIT,
		MARKET
	}
	public static enum POS_SIDE {
		BUY,
		SELL
	}

	private static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder().retryOnConnectionFailure(true)
																		.pingInterval(10, TimeUnit.SECONDS)
																		.build();

	private long requestId = 0;

	private String apiKey, apiSecret;

	public DBRest(String apiKey, String apiSecret) {
		this.apiKey = apiKey;
		this.apiSecret = apiSecret;
	}

	@SuppressWarnings("deprecation")
	public OrderResult placeOrder(POS_TYPE type, POS_SIDE side, long amount, double openPrice, boolean reduceOnly, String customId) {
		if(customId == null || customId.isEmpty()) customId = UUID.randomUUID().toString();

		String body =	"{"+
						"	\"jsonrpc\":	\"2.0\","+
						"	\"id\":			"+(requestId++)+","+
						"	\"method\":		\"private/"+(side == POS_SIDE.BUY ? "buy" : "sell")+"\","+
						"	\"params\": {"+
						"		\"instrument_name\":	\"BTC-PERPETUAL\","+
						"		\"amount\":				"+amount+","+
						"		\"type\":				\""+(type == POS_TYPE.LIMIT ? "limit" : "market")+"\","+

						(type == POS_TYPE.LIMIT ? "\"price\": "+openPrice+"," : "")+

						"		\"time_in_force\":		\"good_til_cancelled\","+

						(type == POS_TYPE.LIMIT ? "\"post_only\": true," : "")+

						"		\"reduce_only\":		"+reduceOnly+","+	// false
						"		\"label\":				\""+customId+"\""+
						"	}"+
						"}";
		body = body.replaceAll("\r\n", "").replaceAll("\t", "").replaceAll(" ", "");

		// validate input data for post 
		try {
			new Moshi.Builder().build().adapter(Object.class).fromJson(body);
		}
		catch(Exception e) {
			return null;			
		}

		// private/buy
		// private/sell

		final Request request = new Request.Builder().url((useTestnet ? TESTNET_REST_API_BASE_URL : REST_API_BASE_URL)+"/api/v2/private/"+(side == POS_SIDE.BUY ? "buy" : "sell"))
														.header("Authorization", "Basic "+Base64.getEncoder().encodeToString((apiKey+":"+apiSecret).getBytes(StandardCharsets.UTF_8)))
														.post(RequestBody.create(MEDIA_TYPE_JSON, body))
														.build();
		try {
			final Response response = HTTP_CLIENT.newCall(request).execute();
			if(response != null) {
				if(response.isSuccessful()) {
					final String json = response.body().string();

					// System.out.println(json);

					if(json != null && !json.isEmpty()) {
						final OrderResponse or = new Moshi.Builder().build().adapter(OrderResponse.class).fromJson(json);
						if(or != null && or.result != null && or.error == null) {
							response.close();

							return or.result;
						}
					}
				}
				else System.out.println(response.body().string());

				response.close();
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	@SuppressWarnings("unused")
	public OHLCSeries getOhlc(String instrument, int period) {
		final OHLCSeries OHLC = new OHLCSeries("");

		final DateTime SDT = DateTimeFormat.forPattern("dd.MM.yyyy").parseDateTime("01.10.2019");
		final DateTime EDT = new DateTime();

		final String params =	"instrument_name="+instrument+			//BTC-PERPETUAL
								"&start_timestamp="+SDT.getMillis()+
								"&end_timestamp="+EDT.getMillis()+
								"&resolution="+period;

		final Request request = new Request.Builder().url((useTestnet ? TESTNET_REST_API_BASE_URL : REST_API_BASE_URL)+"/api/v2/public/get_tradingview_chart_data?"+params)
													.build();
		try {
			final Response response = HTTP_CLIENT.newCall(request).execute();
			if(response != null) {
				if(response.isSuccessful()) {
					final String json = response.body().string();

					final ResultResponse rr = new Moshi.Builder().build().adapter(ResultResponse.class).fromJson(json);
					if(rr != null && rr.result != null && rr.result.status.equals("ok")) {
						DateTime dt;

						for(int key=0; key<rr.result.ticks.size(); key++) {
							dt = new DateTime(rr.result.ticks.get(key));
	
							OHLC.add(
								new FixedMillisecond(rr.result.ticks.get(key)),
								rr.result.open.get(key),
								rr.result.high.get(key),
								rr.result.low.get(key),
								rr.result.close.get(key),
								rr.result.volume.get(key)
							);
						}
					}
				}
				else {
					System.out.println(response.body().string());
				}

				response.close();
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}

		return OHLC;
	}

	public List<UserTrade> getUserTradesByInstrument(String instrument) {
		return getUserTradesByInstrument(instrument, 10);
	}

	public List<UserTrade> getUserTradesByInstrument(String instrument, long count) {
		final String params = "instrument_name="+instrument+"&count="+count+"&sorting=desc";

		final Request request = new Request.Builder().url((useTestnet ? TESTNET_REST_API_BASE_URL : REST_API_BASE_URL)+"/api/v2/private/get_user_trades_by_instrument?"+params)
														.header("Authorization", "Basic "+Base64.getEncoder().encodeToString((apiKey+":"+apiSecret).getBytes(StandardCharsets.UTF_8)))
														.build();
		try {
			final Response response = HTTP_CLIENT.newCall(request).execute();
			if(response != null) {
				if(response.isSuccessful()) {
					final String json = response.body().string();

					// System.out.println(json);

					if(json != null && !json.isEmpty()) {
						final TradesResponse tr = new Moshi.Builder().build().adapter(TradesResponse.class).fromJson(json);
						if(tr != null && tr.result != null && tr.result.trades != null && tr.error == null) {
							return tr.result.trades;
						}
					}
				}
				else System.out.println(response.body().string());
			}

			response.close();
		}
		catch(Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public List<UserTrade> getUserTradesByOrder(String orderId) {
		final String params = "order_id="+orderId+"&sorting=desc";

		final Request request = new Request.Builder().url((useTestnet ? TESTNET_REST_API_BASE_URL : REST_API_BASE_URL)+"/api/v2/private/get_user_trades_by_order?"+params)
														.header("Authorization", "Basic "+Base64.getEncoder().encodeToString((apiKey+":"+apiSecret).getBytes(StandardCharsets.UTF_8)))
														.build();
		try {
			final Response response = HTTP_CLIENT.newCall(request).execute();
			if(response != null) {
				if(response.isSuccessful()) {
					final String json = response.body().string();

					// System.out.println(json);

					if(json != null && !json.isEmpty()) {
						final OrderTradesResponse otr = new Moshi.Builder().build().adapter(OrderTradesResponse.class).fromJson(json);
						if(otr != null && otr.result != null) {
							return otr.result;
						}
					}
				}
				else System.out.println(response.body().string());

				response.close();
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}

		return null;
	}
}

class OrderResponse {
	public String jsonrpc;
	public long id;
	public OrderResult result;
	public Error error;
}

class OrderTradesResponse {
	public String jsonrpc;
	public List<UserTrade> result;
	public long usIn, usOut, usDiff;
}

class TradesResponse {
	public String jsonrpc;
	public ResultTrades result;
	public Error error;
}

class ResultTrades {
	public List<UserTrade> trades;
}

class ResultResponse {
	public Result result;
}

class Result {
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
