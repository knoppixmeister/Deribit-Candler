package rest;

import java.util.List;
import com.squareup.moshi.*;
import candler.*;

public class OrderResult {
	public List<UserTrade> trades;
	public Order order;

	public String toString() {
		return new Moshi.Builder().build().adapter(OrderResult.class).toJson(this);
	}
}
