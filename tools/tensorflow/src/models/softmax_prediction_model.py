
import tensorflow as tf

from src.models.prediction_model import PredictionModel


class SoftmaxPredictionModel(PredictionModel):

    def __init__(self, vocab_sizes=None):
        if vocab_sizes is None:
            self._vocab_sizes = {
                'item': 10
            }
        else:
            self._vocab_sizes = vocab_sizes

    def get_target_paras(self, target, config):
        softmax = tf.keras.layers.Dense(self._vocab_sizes[target], dtype=tf.float32)
        return softmax

    def get_target_prediction_loss(self, user_model, labels, softmax, target, config):
        logits = softmax(user_model)
        losses = tf.nn.sparse_softmax_cross_entropy_with_logits(
            labels=labels, logits=logits)
        loss = tf.reduce_sum(losses)
        return logits, loss

    def get_target_prediction(self, user_model, softmax, target2preds, target, config):
        logits = softmax(user_model)
        target2preds[target] = tf.nn.softmax(logits, name='%s_prob' % target)
