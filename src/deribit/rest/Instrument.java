package deribit.rest;

import com.squareup.moshi.Moshi;

/*
	"tick_size":			0.0005,
	"taker_commission":		0.0004,
	"strike":				6000.0,
	"settlement_period":	"month",
	"quote_currency":		"USD",
	"option_type":			"call",
	"min_trade_amount":		0.1,
	"maker_commission":		0.0004,
	"kind":					"option",
	"is_active":			true,
	"instrument_name":		"BTC-29NOV19-6000-C",
	"expiration_timestamp":	1575014400000,
	"creation_timestamp":	1569573323000,
	"contract_size":		1.0,
	"base_currency":		"BTC"
*/
public class Instrument {
	public String instrument_name, kind, base_currency, quote_currency;
	public boolean is_active;
	public double maker_commission, min_trade_amount, taker_commission, tick_size;
	public long expiration_timestamp, creation_timestamp;

	public String toString() {
		return new Moshi.Builder().build().adapter(Instrument.class).toJson(this);
	}
}
