package classification;

import java.util.List;
import java.util.concurrent.ExecutorService;

import representations.Sequence;

public class SingleLabelSTIFERFClassifier extends AbstractSTIFERFClassifier {

	public SingleLabelSTIFERFClassifier(List<Sequence> train, List<Integer> classIds, int numDimensions,int sequenceDuration, int epsilon, int shapeletFeatureCount, ExecutorService pool) throws Exception {
		super(train, classIds, numDimensions, sequenceDuration, epsilon, shapeletFeatureCount, pool);
	}

}