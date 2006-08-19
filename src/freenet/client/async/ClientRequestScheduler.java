package freenet.client.async;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

import freenet.config.InvalidConfigValueException;
import freenet.config.StringCallback;
import freenet.config.SubConfig;
import freenet.crypt.RandomSource;
import freenet.keys.ClientKeyBlock;
import freenet.keys.KeyVerifyException;
import freenet.node.LowLevelGetException;
import freenet.node.Node;
import freenet.node.RequestScheduler;
import freenet.node.RequestStarter;
import freenet.node.SendableGet;
import freenet.node.SendableRequest;
import freenet.support.Logger;
import freenet.support.SectoredRandomGrabArrayWithClient;
import freenet.support.SectoredRandomGrabArrayWithInt;
import freenet.support.SortedVectorByNumber;

/**
 * Every X seconds, the RequestSender calls the ClientRequestScheduler to
 * ask for a request to start. A request is then started, in its own 
 * thread. It is removed at that point.
 */
public class ClientRequestScheduler implements RequestScheduler {
	
	public class PrioritySchedulerCallback implements StringCallback{
		final ClientRequestScheduler cs;
		
		PrioritySchedulerCallback(ClientRequestScheduler cs){
			this.cs = cs;
		}
		
		public String get(){
			if(cs != null)
				return cs.getChoosenPriorityScheduler();
			else
				return ClientRequestScheduler.PRIORITY_HARD;
		}
		
		public void set(String val) throws InvalidConfigValueException{
			String value;
			if(val == null || val.equalsIgnoreCase(get())) return;
			if(val.equalsIgnoreCase(ClientRequestScheduler.PRIORITY_HARD)){
				value = ClientRequestScheduler.PRIORITY_HARD;
			}else if(val.equalsIgnoreCase(ClientRequestScheduler.PRIORITY_SOFT)){
				value = ClientRequestScheduler.PRIORITY_SOFT;
			}else{
				throw new InvalidConfigValueException("Invalid priority scheme");
			}
			cs.setPriorityScheduler(value);
		}
	}
	
	/**
	 * Structure:
	 * array (by priority) -> // one element per possible priority
	 * SortedVectorByNumber (by # retries) -> // contains each current #retries
	 * RandomGrabArray // contains each element, allows fast fetch-and-drop-a-random-element
	 * 
	 * To speed up fetching, a RGA or SVBN must only exist if it is non-empty.
	 */
	private final SortedVectorByNumber[] priorities;
	// we have one for inserts and one for requests
	final boolean isInsertScheduler;
	final boolean isSSKScheduler;
	final RandomSource random;
	private final HashMap allRequestsByClientRequest;
	private final RequestStarter starter;
	private final Node node;
	public final String name;
	
	public static final String PRIORITY_NONE = "NONE";
	public static final String PRIORITY_SOFT = "SOFT";
	public static final String PRIORITY_HARD = "HARD";
	private String choosenPriorityScheduler; 
	
	private int[] tweakedPrioritySelector = { 
			RequestStarter.MAXIMUM_PRIORITY_CLASS,
			RequestStarter.MAXIMUM_PRIORITY_CLASS,
			RequestStarter.MAXIMUM_PRIORITY_CLASS,
			RequestStarter.MAXIMUM_PRIORITY_CLASS,
			RequestStarter.MAXIMUM_PRIORITY_CLASS,
			RequestStarter.MAXIMUM_PRIORITY_CLASS,
			RequestStarter.MAXIMUM_PRIORITY_CLASS,
			
			RequestStarter.INTERACTIVE_PRIORITY_CLASS,
			RequestStarter.INTERACTIVE_PRIORITY_CLASS,
			RequestStarter.INTERACTIVE_PRIORITY_CLASS,
			RequestStarter.INTERACTIVE_PRIORITY_CLASS,
			RequestStarter.INTERACTIVE_PRIORITY_CLASS,
			RequestStarter.INTERACTIVE_PRIORITY_CLASS,
			
			RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS,
			RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS,
			RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, 
			RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, 
			RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS,
			
			RequestStarter.UPDATE_PRIORITY_CLASS,
			RequestStarter.UPDATE_PRIORITY_CLASS, 
			RequestStarter.UPDATE_PRIORITY_CLASS, 
			RequestStarter.UPDATE_PRIORITY_CLASS,
			
			RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, 
			RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS, 
			RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS,
			
			RequestStarter.PREFETCH_PRIORITY_CLASS, 
			RequestStarter.PREFETCH_PRIORITY_CLASS,
			
			RequestStarter.MINIMUM_PRIORITY_CLASS
	};
	private int[] prioritySelector = {
			RequestStarter.MAXIMUM_PRIORITY_CLASS,
			RequestStarter.INTERACTIVE_PRIORITY_CLASS,
			RequestStarter.IMMEDIATE_SPLITFILE_PRIORITY_CLASS, 
			RequestStarter.UPDATE_PRIORITY_CLASS,
			RequestStarter.BULK_SPLITFILE_PRIORITY_CLASS,
			RequestStarter.PREFETCH_PRIORITY_CLASS,
			RequestStarter.MINIMUM_PRIORITY_CLASS
	};
	
	public ClientRequestScheduler(boolean forInserts, boolean forSSKs, RandomSource random, RequestStarter starter, Node node, SubConfig sc, String name) {
		this.starter = starter;
		this.random = random;
		this.node = node;
		this.isInsertScheduler = forInserts;
		this.isSSKScheduler = forSSKs;
		priorities = new SortedVectorByNumber[RequestStarter.NUMBER_OF_PRIORITY_CLASSES];
		allRequestsByClientRequest = new HashMap();
		
		this.name = name;
		sc.register(name+"_priority_policy", PRIORITY_HARD, name.hashCode(), true, "Priority policy of the "+name+"scheduler", "Set the priority policy scheme used by the scheduler. Could be one of ["+PRIORITY_HARD+", "+PRIORITY_SOFT+"]",
				new PrioritySchedulerCallback(this));
		this.choosenPriorityScheduler = sc.getString(name+"_priority_policy");
	}
	
	/** Called by the  config. Callback
	 * 
	 * @param val
	 */
	protected synchronized void setPriorityScheduler(String val){
		choosenPriorityScheduler = val;
	}
	
	public void register(SendableRequest req) {
		Logger.minor(this, "Registering "+req, new Exception("debug"));
		if((!isInsertScheduler) && (req instanceof ClientPutter))
			throw new IllegalArgumentException("Expected a ClientPut: "+req);
		if(req instanceof SendableGet) {
			SendableGet getter = (SendableGet)req;
			if(!getter.ignoreStore()) {
				ClientKeyBlock block;
				try {
					block = node.fetchKey(getter.getKey(), getter.dontCache());
				} catch (KeyVerifyException e) {
					// Verify exception, probably bogus at source;
					// verifies at low-level, but not at decode.
					getter.onFailure(new LowLevelGetException(LowLevelGetException.DECODE_FAILED));
					return;
				}
				if(block != null) {
					Logger.minor(this, "Can fulfill "+req+" immediately from store");
					getter.onSuccess(block, true);
					return;
				}
			}
		}
		innerRegister(req);
		synchronized(starter) {
			starter.notifyAll();
		}
	}
	
	private synchronized void innerRegister(SendableRequest req) {
		Logger.minor(this, "Still registering "+req+" at prio "+req.getPriorityClass()+" retry "+req.getRetryCount());
		addToGrabArray(req.getPriorityClass(), req.getRetryCount(), req.getClient(), req.getClientRequest(), req);
		HashSet v = (HashSet) allRequestsByClientRequest.get(req.getClientRequest());
		if(v == null) {
			v = new HashSet();
			allRequestsByClientRequest.put(req.getClientRequest(), v);
		}
		v.add(req);
		Logger.minor(this, "Registered "+req+" on prioclass="+req.getPriorityClass()+", retrycount="+req.getRetryCount());
	}
	
	private synchronized void addToGrabArray(short priorityClass, int retryCount, Object client, ClientRequester cr, SendableRequest req) {
		if((priorityClass > RequestStarter.MINIMUM_PRIORITY_CLASS) || (priorityClass < RequestStarter.MAXIMUM_PRIORITY_CLASS))
			throw new IllegalStateException("Invalid priority: "+priorityClass+" - range is "+RequestStarter.MAXIMUM_PRIORITY_CLASS+" (most important) to "+RequestStarter.MINIMUM_PRIORITY_CLASS+" (least important)");
		// Priority
		SortedVectorByNumber prio = priorities[priorityClass];
		if(prio == null) {
			prio = new SortedVectorByNumber();
			priorities[priorityClass] = prio;
		}
		// Client
		SectoredRandomGrabArrayWithInt clientGrabber = (SectoredRandomGrabArrayWithInt) prio.get(retryCount);
		if(clientGrabber == null) {
			clientGrabber = new SectoredRandomGrabArrayWithInt(random, retryCount);
			prio.add(clientGrabber);
			Logger.minor(this, "Registering retry count "+retryCount+" with prioclass "+priorityClass);
		}
		// Request
		SectoredRandomGrabArrayWithClient requestGrabber = (SectoredRandomGrabArrayWithClient) clientGrabber.getGrabber(client);
		if(requestGrabber == null) {
			requestGrabber = new SectoredRandomGrabArrayWithClient(client, random);
			clientGrabber.addGrabber(client, requestGrabber);
		}
		clientGrabber.add(cr, req);
	}
	
	private SortedVectorByNumber removeFirstAccordingToPriorities(int priority){
		SortedVectorByNumber result = null;
		
		short fuzz = -1, iteration = 0;
		synchronized (this) {
			if(choosenPriorityScheduler.equals(PRIORITY_SOFT))
				fuzz = -1;
			else if(choosenPriorityScheduler.equals(PRIORITY_HARD))
				fuzz = 0;	
		}
		// we loop to ensure we try every possibilities ( n + 1)
		//
		// PRIO will do 0,1,2,3,4,5,6,0
		// TWEAKED will do rand%6,0,1,2,3,4,5,6
		while(iteration++ < RequestStarter.NUMBER_OF_PRIORITY_CLASSES + 1){
			priority = fuzz<0 ? tweakedPrioritySelector[random.nextInt(tweakedPrioritySelector.length)] : prioritySelector[Math.abs(fuzz % prioritySelector.length)];
			result = priorities[priority];
			if((result != null) && !result.isEmpty()) {
				Logger.minor(this, "using priority : "+priority);
				return result;
			}
			
			Logger.debug(this, "Priority "+priority+" is null (fuzz = "+fuzz+")");
			fuzz++;
		}
		
		//FIXME: implement NONE
		return null;
	}
	
	public SendableRequest removeFirst() {
		// Priorities start at 0
		Logger.minor(this, "removeFirst()");
		int choosenPriorityClass = Integer.MAX_VALUE;
		SortedVectorByNumber s = removeFirstAccordingToPriorities(choosenPriorityClass);
		if(s != null){
			while(true) {
				SectoredRandomGrabArrayWithInt rga = (SectoredRandomGrabArrayWithInt) s.getFirst();
				if(rga == null) {
					Logger.minor(this, "No retrycount's left");
					break;
				}
				SendableRequest req = (SendableRequest) rga.removeRandom();
				if(rga.isEmpty()) {
					Logger.minor(this, "Removing retrycount "+rga.getNumber());
					s.remove(rga.getNumber());
					if(s.isEmpty()) {
						Logger.minor(this, "Should remove priority ");
					}
				}
				if(req == null) {
					Logger.minor(this, "No requests, retrycount "+rga.getNumber()+" ("+rga+")");
					break;
				}else if(req.getPriorityClass() > choosenPriorityClass) {
					// Reinsert it : shouldn't happen if we are calling reregisterAll,
					// maybe we should ask people to report that error if seen
					Logger.minor(this, "In wrong priority class: "+req);
					innerRegister(req);
					continue;
				}
				
				Logger.minor(this, "removeFirst() returning "+req+" ("+rga.getNumber()+", prio "+req.getPriorityClass()+", retries "+req.getRetryCount()+")");
				ClientRequester cr = req.getClientRequest();
				HashSet v = (HashSet) allRequestsByClientRequest.get(cr);
				v.remove(req);
				if(v.isEmpty())
					allRequestsByClientRequest.remove(cr);
				return req;
			}
		}
		Logger.minor(this, "No requests to run");
		return null;
	}
	
	public void reregisterAll(ClientRequester request) {
		synchronized(this) {
			HashSet h = (HashSet) allRequestsByClientRequest.get(request);
			if(h != null) {
				Iterator i = h.iterator();
				while(i.hasNext()) {
					SendableRequest req = (SendableRequest) i.next();
					// Don't actually remove it as removing it is a rather slow operation
					// It will be removed when removeFirst() reaches it.
					//grabArray.remove(req);
					innerRegister(req);
				}
			}
		}
		synchronized(starter) {
			starter.notifyAll();
		}
	}

	public String getChoosenPriorityScheduler() {
		return choosenPriorityScheduler;
	}
}
