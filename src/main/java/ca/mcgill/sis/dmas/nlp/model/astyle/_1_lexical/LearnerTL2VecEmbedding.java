package ca.mcgill.sis.dmas.nlp.model.astyle._1_lexical;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Iterables;

import ca.mcgill.sis.dmas.io.collection.DmasCollectionOperations;
import ca.mcgill.sis.dmas.io.collection.EntryPair;
import ca.mcgill.sis.dmas.io.collection.EntryTriplet;
import ca.mcgill.sis.dmas.io.collection.IteratorSafeGen;
import ca.mcgill.sis.dmas.io.collection.Pool;
import ca.mcgill.sis.dmas.nlp.corpus.Sentence;
import ca.mcgill.sis.dmas.nlp.model.astyle.Document;
import ca.mcgill.sis.dmas.nlp.model.astyle.GradientProgress;
import ca.mcgill.sis.dmas.nlp.model.astyle.NodeWord;
import ca.mcgill.sis.dmas.nlp.model.astyle.Param;
import ca.mcgill.sis.dmas.nlp.model.astyle.RandL;
import ca.mcgill.sis.dmas.nlp.model.astyle.WordEmbedding;

import static ca.mcgill.sis.dmas.nlp.model.astyle.MathUtilities.*;
import static java.util.stream.Collectors.*;

import java.util.ArrayList;

import static java.lang.Math.sqrt;

public class LearnerTL2VecEmbedding {

	private static Logger logger = LoggerFactory.getLogger(LearnerTL2VecEmbedding.class);

	public static class TL2VParam extends Param {
		private static final long serialVersionUID = -817341338942724187L;
		public int min_freq = 3;
		public int vec_dim = 200;
		public double optm_subsampling = 1e-4;
		public double optm_initAlpha = 0.05;
		public int optm_window = 8;
		public int optm_negSample = 25;
		public int optm_parallelism = 1;
		public int optm_iteration = 20;
		public int optm_aphaUpdateInterval = 10000;

	}

	public Map<String, NodeWord> vocab = null;
	public Map<String, NodeWord> trainDocLexicMap = null;
	public Map<String, NodeWord> trainDocTopicMap = null;
	public List<NodeWord> vocabL = null;
	public int[] pTable;
	public transient volatile double alpha;
	public transient volatile long tknCurrent;
	public transient volatile long tknLastUpdate;
	public volatile long tknTotal;
	public volatile boolean debug = true;
	public TL2VParam param;

	private void preprocess(Iterable<Document> documents) {

		vocab = null;

		// frequency map:
		final HashMap<String, Long> counter = new HashMap<>();
		documents.forEach(doc -> doc.forEach(sent -> sent
				.forEach(token -> counter.compute(token.trim().toLowerCase(), (w, c) -> c == null ? 1 : c + 1))));

		// add additional word (for /n)
		counter.put("</s>", Long.MAX_VALUE);

		// create word nodes (matrix)
		vocab = counter.entrySet().stream().parallel().filter(en -> en.getValue() >= param.min_freq)
				.collect(toMap(Map.Entry::getKey, p -> new NodeWord(p.getKey(), p.getValue())));

		// total valid word count
		tknTotal = vocab.values().stream().filter(w -> !w.token.equals("</s>")).mapToLong(w -> w.freq).sum();

		// vocabulary list (sorted)
		vocabL = vocab.values().stream().sorted((a, b) -> b.token.compareTo(a.token))
				.sorted((a, b) -> Double.compare(b.freq, a.freq)).collect(toList());

		// reset frequency for /n
		vocabL.get(0).freq = 0;

		// initialize matrix:
		RandL rd = new RandL(1);
		vocabL.stream().forEach(node -> node.init(param.vec_dim, rd));

		// sub-sampling probability
		if (param.optm_subsampling > 0) {
			double fcount = param.optm_subsampling * tknTotal;
			vocabL.stream().parallel().forEach(w -> w.samProb = (sqrt(w.freq / fcount) + 1) * fcount / w.freq);
		}

		pTable = createPTbl(vocabL, (int) 1e8, 0.75);

		if (debug)
			logger.info("Vocab {}; Total {};", vocabL.size(), tknTotal);

		trainDocLexicMap = new HashMap<>();
		trainDocTopicMap = new HashMap<>();
		documents.forEach(doc -> trainDocLexicMap.put(doc.id, new NodeWord(doc.id, 1)));
		documents.forEach(doc -> trainDocTopicMap.put(doc.id, new NodeWord(doc.id, 1)));
		trainDocLexicMap.values().forEach(node -> node.initInLayer(this.param.vec_dim, rd));
		trainDocTopicMap.values().forEach(node -> node.initOutLayer(this.param.vec_dim));

	}

	private void gradientDecend(final Iterable<Document> documents, Map<String, NodeWord> docLexicMap,
			Map<String, NodeWord> docTopicMap, long numTkns, long alphaUpdateInterval) throws InterruptedException, ExecutionException {
		tknLastUpdate = 0;
		tknCurrent = 0;
		// training
		GradientProgress p = new GradientProgress(numTkns * param.optm_iteration);
		if (debug)
			p.start(logger);

		// new list of topic nodes for ng-sampling:
		ArrayList<NodeWord> docTL = new ArrayList<>(trainDocTopicMap.values());

		// thread-safe batch consumer generator:
		final IteratorSafeGen<Document> gen = new IteratorSafeGen<>(documents, 100, param.optm_iteration);

		new Pool(param.optm_parallelism).start(indx -> {
			RandL rl = new RandL(indx);
			double[] bfIn = new double[param.vec_dim], bfNeul1e = new double[param.vec_dim];
			gen.subIterable().forEach(doc -> {
				for (Sentence sent : doc) {

					// update alpha:
					if (tknCurrent - tknLastUpdate > alphaUpdateInterval) {
						alpha = param.optm_initAlpha * (1.0 - 1.0 * tknCurrent / (numTkns * param.optm_iteration + 1));
						//alpha = alpha < param.optm_initAlpha * 0.0001 ? param.optm_initAlpha * 0.0001 : alpha;
						alpha = alpha < param.optm_initAlpha * 0.00001 ? param.optm_initAlpha * 0.00001 : alpha;
						//alpha = alpha < 0.0001 ? 0.0001 : alpha;
						if (debug)
							p.report(logger, tknCurrent, alpha);
						tknLastUpdate = tknCurrent;
					}

					// dictionary lookup & sub-sampling
					List<NodeWord> nsent = Arrays.stream(sent.tokens)
							//
							.map(tkn -> vocab.get(tkn.trim().toLowerCase()))//
							.filter(notNull)//
							.peek(node -> tknCurrent++)//
							.filter(n -> n.samProb >= rl.nextF()) //
							.collect(toList());

					if (!docLexicMap.containsKey(doc.id)) {
						logger.error("Critical error. Doc node not found {}", doc);
						return;
					}

					iterate(docTL, nsent, docLexicMap.get(doc.id), docTopicMap.get(doc.id), rl, bfIn, bfNeul1e);
				}
			});
		}).waiteForCompletion();
		if (debug)
			p.complete(logger);
	}

	// negative sampling
	private void iterate(List<NodeWord> docTL, List<NodeWord> nsent, NodeWord docLexicNode, NodeWord docTopicNode,
			RandL rl, double[] bfIn, double[] bfNeul1e) {

		Supplier<NodeWord> wordNgMethod = () -> vocabL.get(pTable[//
		(int) Long.remainderUnsigned(rl.nextR() >>> 16, pTable.length)//
		]);

		Supplier<NodeWord> docTNgMethod = () -> docTL.get(//
				(int) Long.remainderUnsigned(rl.nextR() >>> 16, docTL.size())//
		);

		slidingWnd(nsent, param.optm_window, rl).forEach(cont -> {

			// lexical:
			Arrays.fill(bfIn, 0.0);
			Arrays.fill(bfNeul1e, 0.0);
			cont.value.stream().forEach(src -> add(bfIn, src.neuIn));
			add(bfIn, docLexicNode.neuIn);
			div(bfIn, cont.value.size() + 1);
			ngSamp(cont.key, bfIn, bfNeul1e, wordNgMethod);
			cont.value.stream().filter(src -> !src.fixed).forEach(src -> add(src.neuIn, bfNeul1e));
			add(docLexicNode.neuIn, bfNeul1e);

			// topical
			Arrays.fill(bfIn, 0.0);
			Arrays.fill(bfNeul1e, 0.0);
			cont.value.stream().forEach(src -> add(bfIn, src.neuIn));
			div(bfIn, cont.value.size());
			ngSamp(docTopicNode, bfIn, bfNeul1e, docTNgMethod);
			cont.value.stream().filter(src -> !src.fixed).forEach(src -> add(src.neuIn, bfNeul1e));
		});

	}

	private void ngSamp(NodeWord tar, double[] in, double[] neul1e, Supplier<NodeWord> ngMethod) {
		for (int i = 0; i < param.optm_negSample + 1; ++i) {
			double label;
			NodeWord nodeOut;
			// NodeWord target;
			if (i == 0) {
				label = 1;
				nodeOut = tar;
			} else {
				label = 0;
				NodeWord rtar = ngMethod.get();
				if (rtar == tar)
					continue;
				nodeOut = rtar;
			}
			double f = exp(dot(in, nodeOut.neuOut));
			double g = (label - f) * alpha;
			dxpay(neul1e, nodeOut.neuOut, g);
			if (!nodeOut.fixed)
				dxpay(nodeOut.neuOut, in, g);
		}
	}

	public void train(Iterable<Document> docs) throws InterruptedException, ExecutionException {
		alpha = param.optm_initAlpha;
		preprocess(docs);
		gradientDecend(docs, trainDocLexicMap, trainDocTopicMap, tknTotal, param.optm_aphaUpdateInterval);
		fixTrainedModel();
	}

	private void fixTrainedModel() {
		vocab.values().stream().forEach(node -> node.fixed = true);
		trainDocLexicMap.values().stream().forEach(node -> node.fixed = true);
		trainDocTopicMap.values().stream().forEach(node -> node.fixed = true);
	}

	public TLEmbedding inferUnnormalized(Iterable<Document> docs) {
		alpha = param.optm_initAlpha;
		try {
			Iterable<Document> fdocs = Iterables.filter(docs, doc -> !trainDocLexicMap.containsKey(doc.id));

			HashMap<String, NodeWord> inferDocLexicMap = new HashMap<>();
			HashMap<String, NodeWord> inferDocTopicMap = new HashMap<>();
			fdocs.forEach(doc -> inferDocLexicMap.put(doc.id, new NodeWord(doc.id, 1)));
			fdocs.forEach(doc -> inferDocTopicMap.put(doc.id, new NodeWord(doc.id, 1)));
			RandL rd = new RandL(1);
			inferDocLexicMap.values().forEach(node -> node.initInLayer(this.param.vec_dim, rd));
			inferDocTopicMap.values().forEach(node -> node.initOutLayer(this.param.vec_dim));

			long tknTotalInDocs = StreamSupport.stream(fdocs.spliterator(), false)
					.flatMap(doc -> doc.sentences.stream()).flatMap(sent -> Arrays.stream(sent.tokens))
					.filter(tkn -> vocab.containsKey(tkn)).count();

			gradientDecend(fdocs, inferDocLexicMap, inferDocTopicMap, tknTotalInDocs, 0);

			TLEmbedding embeddings = new TLEmbedding();

			embeddings.lexicEmbedding = inferDocLexicMap.entrySet()//
					.stream()//
					.map(ent -> new EntryPair<>(ent.getKey(), ent.getValue().neuIn))
					.collect(Collectors.toMap(ent -> ent.key, ent -> ent.value));

			embeddings.topicEmbedding = inferDocTopicMap.entrySet()//
					.stream()//
					.map(ent -> new EntryPair<>(ent.getKey(), ent.getValue().neuOut))
					.collect(Collectors.toMap(ent -> ent.key, ent -> ent.value));

			StreamSupport.stream(docs.spliterator(), false)//
					.map(doc -> new EntryPair<>(trainDocLexicMap.get(doc.id), trainDocTopicMap.get(doc.id)))//
					.filter(entry -> entry.key != null)//
					.forEach(entry -> {
						embeddings.lexicEmbedding.put(entry.key.token, entry.key.neuIn);
						embeddings.topicEmbedding.put(entry.value.token, entry.key.neuOut);
					});

			return embeddings;
		} catch (Exception e) {
			logger.info("Failed to learn new doc vector.", e);
			return null;
		}
	}

	public WordEmbedding produce() {
		WordEmbedding embedding = new WordEmbedding();
		embedding.vocabL = vocabL.stream().map(node -> new EntryPair<>(node.token, convertToFloat(node.neuIn)))
				.collect(toList());
		try {
			embedding.param = (new ObjectMapper()).writeValueAsString(this.param);
		} catch (JsonProcessingException e) {
			logger.error("Failed to serialize the parameter. ", e);
		}
		return embedding;
	}

	public TLEmbedding produceDocEmbdUnnormalized() {
		TLEmbedding embedding = new TLEmbedding();
		embedding.lexicEmbedding = trainDocLexicMap.entrySet().stream()
				.collect(toMap(ent -> ent.getKey(), ent -> ent.getValue().neuIn));
		embedding.topicEmbedding = trainDocTopicMap.entrySet().stream()
				.collect(toMap(ent -> ent.getKey(), ent -> ent.getValue().neuOut));
		return embedding;
	}

	public LearnerTL2VecEmbedding(TL2VParam param) {
		this.param = param;
	}
	
	public static class TLEmbedding {
		public Map<String, double[]> topicEmbedding;
		public Map<String, double[]> lexicEmbedding;
	}
}
