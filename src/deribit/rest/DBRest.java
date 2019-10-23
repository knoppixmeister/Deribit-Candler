package deribit.rest;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import org.jfree.data.time.*;
import org.jfree.data.time.ohlc.*;
import org.joda.time.*;
import org.joda.time.format.*;
import com.squareup.moshi.*;
import deribit.candler.*;
import okhttp3.*;

public class DBRest {
	public static final String REST_API_BASE_URL			=	"https://www.deribit.com";
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

		final DateTime SDT = DateTimeFormat.forPattern("dd.MM.yyyy").parseDateTime("01.09.2019");
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

	public List<UserTrade> getUserTradesByInstrument(final String instrument, long count) {
		if(count > 1000) count = 1000;

		/*
		final String url = "https://www.deribit.com/api/v2/public/auth?grant_type=client_credentials&client_id="+apiKey+"&client_secret="+apiSecret;
		try {
			Response response = HTTP_CLIENT.newCall(new Request.Builder().url(url).build()).execute();
			if(response == null) return null;

			String json = response.body().string();

			System.out.println(json.replaceAll(",", ",\r\n"));

			response.close();

			final UserAuthResponse uar = new Moshi.Builder().build().adapter(UserAuthResponse.class).fromJson(json);
			if(uar != null && uar.result != null) {
				// uar.refresh_token;
				// uar.access_token;
				
				System.out.println("UAR_ACCE_TKN: "+uar.result.access_token);

				final String params = "instrument_name="+instrument+"&count="+count+"&sorting=desc";

				final Request request = new Request.Builder().url((useTestnet ? TESTNET_REST_API_BASE_URL : REST_API_BASE_URL)+"/api/v2/private/get_user_trades_by_instrument?"+params)
																.header("Authorization", "bearer "+uar.result.access_token)
																.build();
				try {
					response = HTTP_CLIENT.newCall(request).execute();
					if(response != null) {
						if(response.isSuccessful()) {
							json = response.body().string();

							System.out.println(json);
						}
						else System.out.println(response.body().string());
					}
					else System.out.println("444");
				}
				catch(Exception e1) {
					e1.printStackTrace();
				}
				
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		*/

		final String params = "instrument_name="+instrument+"&count="+count+"&sorting=desc&include_old=true";

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

				response.close();
			}
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

	public List<Deposit> getDeposits() {
		final String params = "currency=BTC&count=1000&offset=0";

		final Request request = new Request.Builder().url((useTestnet ? TESTNET_REST_API_BASE_URL : REST_API_BASE_URL)+"/api/v2/private/get_deposits?"+params)
														.header("Authorization", "Basic "+Base64.getEncoder().encodeToString((apiKey+":"+apiSecret).getBytes(StandardCharsets.UTF_8)))
														.build();
		try {
			final Response response = HTTP_CLIENT.newCall(request).execute();
			if(response != null) {
				if(response.isSuccessful()) {
					final String json = response.body().string();

					if(json == null || json.isEmpty()) return null;

					final DepositsResponse dr = new Moshi.Builder().build().adapter(DepositsResponse.class).fromJson(json);
					if(dr == null || dr.result == null || dr.result.data == null) return null;

					return dr.result.data;
				}

				response.close();
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	// 100897 - profit_bank
	public boolean transferToSubaccount(double amount, long destionationAccountId) {
		/*
			"method": "private/submit_transfer_to_subaccount",
			"params": {
				"currency":		"ETH",
				"amount":		12.1234,
				"destination":	20
			}
		 */

		final String params = "currency=BTC&amount="+String.format(Locale.US, "%.8f", amount)+"&destination="+destionationAccountId;

		final Request request = new Request.Builder().url((useTestnet ? TESTNET_REST_API_BASE_URL : REST_API_BASE_URL)+"/api/v2/private/submit_transfer_to_subaccount?"+params)
														.header("Authorization", "Basic "+Base64.getEncoder().encodeToString((apiKey+":"+apiSecret).getBytes(StandardCharsets.UTF_8)))
														.build();
		try {
			final Response response = HTTP_CLIENT.newCall(request).execute();
			if(response != null) {
				if(response.isSuccessful()) {
					final String json = response.body().string();

					if(json == null || json.isEmpty()) return false;

					/*
						{
							"jsonrpc":	"2.0",
							"result":	{
								"updated_timestamp":	1571690120153,
								"type":					"subaccount",
								"state":				"confirmed",
								"other_side":			"profit_bank",
								"id":					80053,
								"direction":			"payment",
								"currency":				"BTC",
								"created_timestamp":	1571690120153,
								"amount":				2.8e-7
							},
							"usIn":		1571690120126887,
							"usOut":	1571690120156134,
							"usDiff":	29247,
							"testnet":	false
						}
					*/

					System.out.println(json);

					return true;
				}
				else System.out.println(response.body().string());

				response.close();
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}

		return false;
	}

	public List<Transfer> getTransfers() {
		/*
			"method" : "private/get_transfers",
			"params" : {
			    "currency" : "BTC",
			    "count" : 10,
			    offset: 0
			}
		 */

		final String params = "currency=BTC&count=10000&offset=0";

		final Request request = new Request.Builder().url((useTestnet ? TESTNET_REST_API_BASE_URL : REST_API_BASE_URL)+"/api/v2/private/get_transfers?"+params)
														.header("Authorization", "Basic "+Base64.getEncoder().encodeToString((apiKey+":"+apiSecret).getBytes(StandardCharsets.UTF_8)))
														.build();
		try {
			final Response response = HTTP_CLIENT.newCall(request).execute();
			if(response != null) {
				if(response.isSuccessful()) {
					final String json = response.body().string();

					if(json == null || json.isEmpty()) return null;

					System.out.println(json);

					TransferResponse tr = new Moshi.Builder().build().adapter(TransferResponse.class).fromJson(json);
					if(tr == null || tr.error != null || tr.result == null || tr.result.data == null) return null;

					return tr.result.data;
				}

				response.close();
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	public List<Instrument> getInstruments() {
		return getInstruments(null);
	}

	/*
		KIND:

		future
		option
	 */
	public List<Instrument> getInstruments(String kind) {
		final String params = "currency=BTC"+(kind != null && !kind.isEmpty() ? "&kind="+kind : "");

		final Request request = new Request.Builder().url((useTestnet ? TESTNET_REST_API_BASE_URL : REST_API_BASE_URL)+"/api/v2/public/get_instruments?"+params)
														.header("Authorization", "Basic "+Base64.getEncoder().encodeToString((apiKey+":"+apiSecret).getBytes(StandardCharsets.UTF_8)))
														.build();
		try {
			final Response response = HTTP_CLIENT.newCall(request).execute();
			if(response != null) {
				if(response.isSuccessful()) {
					final String json = response.body().string();

					if(json == null || json.isEmpty()) return null;

					InstrumentsResponse ir = new Moshi.Builder().build().adapter(InstrumentsResponse.class).fromJson(json);
					if(ir == null || ir.error != null || ir.result == null) return null;

					return ir.result;
				}

				response.close();
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}

		return null;
	}
	
	public void getAccountSummary() {
		// /private/get_account_summary
		
		/*
		String body =	"{"+
						"	\"jsonrpc\":	\"2.0\","+
						"	\"id\":			1,"+
						"	\"method\" : \"private/get_account_summary\",\r\n" + 
						"	\"params\" : {\r\n" + 
						"		\"currency\" : \"BTC\",\r\n" + 
						"		\"extended\" : true\r\n" + 
						"	}"+
						"}";
		*/

		final String params = "currency=BTC&extended=true";

		final Request request = new Request.Builder().url((useTestnet ? TESTNET_REST_API_BASE_URL : REST_API_BASE_URL)+"/api/v2/private/get_account_summary?"+params)
														.header("Authorization", "Basic "+Base64.getEncoder().encodeToString((apiKey+":"+apiSecret).getBytes(StandardCharsets.UTF_8)))
														.build();
		try {
			final Response response = HTTP_CLIENT.newCall(request).execute();
			if(response != null) {
				if(response.isSuccessful()) {
					final String json = response.body().string();

					if(json == null || json.isEmpty()) return;

					System.out.println(
						json.replaceAll(",", ",\r\n").replaceAll("\\{", "{\r\n")
					);
				}

				response.close();
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}

		return;
	}

	public void getOpenOrders() {
		final String params = "instrument_name=BTC-PERPETUAL&type=all";

		final Request request = new Request.Builder().url((useTestnet ? TESTNET_REST_API_BASE_URL : REST_API_BASE_URL)+"/api/v2/private/get_open_orders_by_instrument?"+params)
														.header("Authorization", "Basic "+Base64.getEncoder().encodeToString((apiKey+":"+apiSecret).getBytes(StandardCharsets.UTF_8)))
														.build();
		try {
			final Response response = HTTP_CLIENT.newCall(request).execute();
			if(response != null) {
				if(response.isSuccessful()) {
					final String json = response.body().string();

					if(json == null || json.isEmpty()) return;

					System.out.println(
						json.replaceAll(",", ",\r\n").replaceAll("\\{", "{\r\n").replaceAll("}", "\r\n}")
					);
				}
				else System.out.println(response.body().string());

				response.close();
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}

		return;
	}
}

class BaseResponse {
	public String jsonrpc;
	public long id;
	public Error error;
	public long usIn, usOut, usDiff;
	public boolean testnet;
}

class TransferResponse extends BaseResponse {
	public TransferResponseResult result; 
}

class TransferResponseResult {
	public List<Transfer> data;
}

class InstrumentsResponse extends BaseResponse {
	public List<Instrument> result;
}

class DepositsResponse extends BaseResponse {
	public DepositsResponseResult result;
}

class DepositsResponseResult {
	public List<Deposit> data;
	public long count;
}

/*
		[
			{
				"updated_timestamp":1571650845811,
				"transaction_id":"f2796fc06b23be7fc1c0806ced32ec6f96bc200eb582c75c1cecb55f5039c5ff",
				"state":"completed",
				"received_timestamp":1571650591571,
				"currency":"BTC",
				"amount":0.00454018,
				"address":"3879XZzxNEur63XGpNWaPiaKCbydSDn2in"
			}
		],
		"count":1
	},
 */
class Deposit {
	public long updated_timestamp, received_timestamp;
	public String transaction_id, state, currency, address;
	public double amount;
}

class UserAuthResponse extends BaseResponse {
	public UserAuthResult result;
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
