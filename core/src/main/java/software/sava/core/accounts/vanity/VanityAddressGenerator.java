package software.sava.core.accounts.vanity;

import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public interface VanityAddressGenerator {

  static VanityAddressGenerator createGenerator(final Path keyPath,
                                                final SecureRandomFactory secureRandomFactory,
                                                final boolean sigVerify,
                                                final ExecutorService executor,
                                                final int numThreads,
                                                final Subsequence beginsWith,
                                                final Subsequence endsWith,
                                                final long findKeys) {
    if (findKeys > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Max find keys is " + Integer.MAX_VALUE);
    } else {
      try {
        final var found = new AtomicInteger(0);
        final var searched = new AtomicLong(0);
        final int checkFound = 262_144;
        final var results = new ArrayBlockingQueue<Result>(checkFound * numThreads);
        for (int i = 0; i < numThreads; ++i) {
          final var secureRandom = secureRandomFactory.createSecureRandom();
          final var worker = endsWith == null
              ? new BeginsWithMaskWorker(
              keyPath,
              secureRandom,
              sigVerify,
              beginsWith,
              findKeys,
              found,
              searched,
              results,
              checkFound
          )
              : new MaskWorker(
              keyPath,
              secureRandom,
              sigVerify,
              beginsWith,
              endsWith,
              findKeys,
              found,
              searched,
              results,
              checkFound
          );
          executor.execute(worker);
        }
        return new ConcurrentVanityAddressGenerator(findKeys, results, found, searched);
      } catch (final NoSuchAlgorithmException e) {
        throw new RuntimeException(e);
      }
    }
  }

  static VanityAddressGenerator createGenerator(final Path keyPath,
                                                final boolean sigVerify,
                                                final ExecutorService executor,
                                                final int numThreads,
                                                final Subsequence beginsWith,
                                                final Subsequence endsWith,
                                                final long findKeys) {
    return createGenerator(
        keyPath,
        SecureRandomFactory.DEFAULT,
        sigVerify,
        executor,
        numThreads,
        beginsWith,
        endsWith,
        findKeys
    );
  }

  int numFound();

  long numSearched();

  void breakOut();

  Result take() throws InterruptedException;

  Result poll(final long timeout, final TimeUnit timeUnit) throws InterruptedException;
}
