package candler;

public interface UserOrderListener {
	public void onNewOrder(Order order,	final String instrument, final String rawData);
}
