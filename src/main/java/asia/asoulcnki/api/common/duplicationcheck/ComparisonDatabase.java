package asia.asoulcnki.api.common.duplicationcheck;

import asia.asoulcnki.api.persistence.entity.Reply;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class ComparisonDatabase {
	public static final String DEFAULT_IMAGE_PATH = "data/database.dat";
	private final static Logger log = LoggerFactory.getLogger(ComparisonDatabase.class);
	private final static int initialCapacity = 100 * 10000;
	private static ComparisonDatabase instance;
	private transient ReadWriteLock rwLock;

	private int minTime;
	private int maxTime;
	private long maxRpid;
	// reply id -> reply
	private Map<Long, Reply> replyMap;
	//  text hash -> reply ids
	private Map<Long, ArrayList<Long>> textHashMap;

	private ComparisonDatabase() {
		this.maxRpid = 0;
		this.minTime = Integer.MAX_VALUE;
		this.maxTime = 0;
		this.rwLock = new ReentrantReadWriteLock();
		this.replyMap = new HashMap<>(initialCapacity);
		this.textHashMap = new HashMap<>(initialCapacity);
	}

	public static synchronized ComparisonDatabase getInstance() {
		if (instance == null) {
			synchronized (ComparisonDatabase.class) {
				if (instance == null) {
					try {
						long start = System.currentTimeMillis();
						log.info("start to load comparison database...");
						instance = loadFromImage(DEFAULT_IMAGE_PATH);
						log.info("load database cost {} ms", System.currentTimeMillis() - start);
					} catch (Exception e) {
						instance = new ComparisonDatabase();
					}
					instance.rwLock = new ReentrantReadWriteLock();
				}
			}
		}
		return instance;
	}

	static void printMemory() {
		log.info("max memory {}", mb(Runtime.getRuntime().maxMemory()));
		log.info("total memory {}", mb(Runtime.getRuntime().totalMemory()));
		log.info("free memory {}", mb(Runtime.getRuntime().freeMemory()));
	}

	static String mb(long s) {
		return String.format("%d (%.2f M)", s, (double) s / (1024 * 1024));
	}

	private static ComparisonDatabase loadFromImage(String path) throws IOException {
		Kryo kryo = new Kryo();
		File file = new File(path);
		log.info("before alloc buffer");
		printMemory();
		Input input = new Input(new FileInputStream(file), 1024 * 1000 * 100);
		log.info("after alloc buffer");
		printMemory();
		ComparisonDatabase db = kryo.readObject(input, ComparisonDatabase.class);
		log.info("after de-serialize");
		printMemory();
		input.close();
		return db;
	}

	// return: key-> rpid, value -> hit count
	public static List<Map.Entry<Long, Integer>> searchRelatedReplies(ArrayList<Long> textHashList, int minHit) {
		float threshold = (float) (textHashList.size() * 0.2);
		if (threshold <= minHit) {
			threshold = minHit;
		}
		return searchRelatedReplies(textHashList, threshold);
	}

	public static List<Map.Entry<Long, Integer>> searchRelatedReplies(ArrayList<Long> textHashList, float threshold) {
		Map<Long, Integer> replyHitMap = new HashMap<>();
		for (Long textHash : textHashList) {
			ArrayList<Long> hitReplyIds = ComparisonDatabase.getInstance().searchHash(textHash);
			if (hitReplyIds != null) {
				for (long id : hitReplyIds) {
					if (replyHitMap.containsKey(id)) {
						int count = replyHitMap.get(id);
						replyHitMap.put(id, count + 1);
					} else {
						replyHitMap.put(id, 1);
					}
				}
			}
		}
		Comparator<Map.Entry<Long, Integer>> cmp = Map.Entry.comparingByValue();
		return replyHitMap.entrySet().stream().filter(entry -> entry.getValue() > threshold).sorted(cmp.reversed()).collect(Collectors.toList());
	}

	public void readLock() {
		this.rwLock.readLock().lock();
	}

	public void readUnLock() {
		this.rwLock.readLock().unlock();
	}

	public void writeLock() {
		this.rwLock.writeLock().lock();
	}

	public void writeUnLock() {
		this.rwLock.writeLock().unlock();
	}

	public void dumpToImage(String path) throws IOException {
		Kryo kryo = new Kryo();
		File file = new File(path);
		Output output = new Output(new FileOutputStream(file));
		kryo.writeObject(output, this);
		output.close();
	}

	public void reset() {
		this.writeLock();
		try {
			this.maxRpid = 0;
			this.minTime = Integer.MAX_VALUE;
			this.maxTime = 0;
			this.replyMap = new HashMap<>(initialCapacity);
			this.textHashMap = new HashMap<>(initialCapacity);
		} finally {
			this.writeUnLock();
		}
	}

	public void addReplyData(Reply reply) {
		if (reply == null || replyMap.containsKey(reply.getRpid())) {
			return;
		}

		String content = ArticleCompareUtil.trim(reply.getContent());
		int codePointCount = content.codePointCount(0, content.length());

		if (codePointCount < SummaryHash.DEFAULT_K) {
			return;
		}

		this.replyMap.put(reply.getRpid(), reply);

		if (reply.getCtime() > this.maxTime) {
			this.maxTime = reply.getCtime();
		}
		if (reply.getCtime() < this.minTime) {
			this.minTime = reply.getCtime();
		}
		if (reply.getRpid() > this.maxRpid) {
			this.maxRpid = reply.getRpid();
		}

		ArrayList<Long> textHashList = SummaryHash.defaultHash(content);

		// calculate IF
		if (codePointCount > 40) {
			// key-> rpid, value -> hit count
			List<Map.Entry<Long, Integer>> sortedRelatedReplies = searchRelatedReplies(textHashList, 2);

			for (Map.Entry<Long, Integer> entry : sortedRelatedReplies) {
				Reply relatedReply = this.getReply(entry.getKey());
				// we only process original reply
				if (relatedReply.getOriginRpid() >= 0) {
					continue;
				}
				String relatedContent = ArticleCompareUtil.trim(relatedReply.getContent());
				int relatedContentLength = ArticleCompareUtil.textLength(relatedContent);
				float similarity = ArticleCompareUtil.compareArticle(content, relatedContent);
				if (ArticleCompareUtil.isHighSimilarity(relatedContentLength, similarity)) {
					reply.setOriginRpid(relatedReply.getRpid());
					relatedReply.setSimilarCount(relatedReply.getSimilarCount() + 1);
					break;
				}
			}
		}

		// add text hash to search database
		for (final Long textHash : textHashList) {
			if (!textHashMap.containsKey(textHash)) {
				textHashMap.put(textHash, new ArrayList<>());
			}
			textHashMap.get(textHash).add(reply.getRpid());
		}

		if (replyMap.size() % 20000 == 0) {
			log.info("reply size: {}", replyMap.size());
		}
	}

	public Reply getReply(long rpid) {
		try {
			this.readLock();
			return replyMap.get(rpid);
		} finally {
			this.readUnLock();
		}
	}

	public ArrayList<Long> searchHash(long textHash) {
		try {
			this.readLock();
			return this.textHashMap.get(textHash);
		} finally {
			this.readUnLock();
		}
	}

	public int getMinTime() {
		return minTime;
	}

	public int getMaxTime() {
		return maxTime;
	}

}
