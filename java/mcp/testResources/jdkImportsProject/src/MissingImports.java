// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

/**
 * The collections and the concurrency part of the field report, with nothing imported.
 *
 * Every type is named by its simple name. Two packages are over the code style counter that joins a
 * group of imports into an import on demand, and the platform used to join `java.util` and
 * `java.util.concurrent` here, after which the file did not compile.
 *
 * No comment of this file may spell an import on demand. The tests look for that text in the whole
 * file, so a comment that holds it makes a test pass, or fail, for the wrong reason.
 *
 * Two things differ from the report. The mock JDK carries no `java.time`, so the timestamp of an item
 * is gone. A record needs a language level above the one of the mock JDK, so `Item` is a class.
 */
public class MissingImports {

  enum Kind {SMALL, MEDIUM, LARGE}

  static final class Item {
    final UUID id;
    final String name;
    final Kind kind;
    final BigDecimal price;

    Item(UUID id, String name, Kind kind, BigDecimal price) {
      this.id = id;
      this.name = name;
      this.kind = kind;
      this.price = price;
    }

    String name() {
      return name;
    }

    Kind kind() {
      return kind;
    }

    BigDecimal price() {
      return price;
    }
  }

  private final AtomicInteger counter = new AtomicInteger();
  private final ConcurrentMap<String, Integer> hits = new ConcurrentHashMap<>();
  private final List<String> log = new CopyOnWriteArrayList<>();

  String collections() {
    List<Item> items = new ArrayList<>();
    items.add(new Item(UUID.randomUUID(), "alpha", Kind.SMALL, BigDecimal.TEN));

    Map<String, Item> byName = new LinkedHashMap<>();
    SortedMap<String, BigDecimal> prices = new TreeMap<>();
    NavigableSet<String> names = new TreeSet<>();
    Set<String> unique = new HashSet<>();
    Deque<String> deque = new ArrayDeque<>();
    Queue<String> queue = new PriorityQueue<>(Comparator.naturalOrder());
    LinkedList<String> linked = new LinkedList<>();
    EnumMap<Kind, Integer> perKind = new EnumMap<>(Kind.class);
    EnumSet<Kind> kinds = EnumSet.allOf(Kind.class);
    BitSet bits = new BitSet(32);
    Iterator<Item> iterator = items.iterator();
    ListIterator<String> listIterator = linked.listIterator();
    Collection<String> view = Collections.unmodifiableCollection(unique);
    Optional<Item> first = items.stream().findFirst();
    Random random = new Random(1);
    Locale locale = Locale.ROOT;

    while (iterator.hasNext()) {
      Item it = iterator.next();
      byName.put(it.name(), it);
      prices.put(it.name(), it.price());
      names.add(it.name());
      unique.add(it.name());
      deque.push(it.name());
      queue.offer(it.name());
      linked.add(it.name());
      perKind.merge(it.kind(), 1, Integer::sum);
      bits.set(random.nextInt(32));
      hits.merge("seen", 1, Integer::sum);
      counter.incrementAndGet();
      log.add(it.name());
    }

    Stream<String> stream = names.stream();
    Map<Kind, List<String>> grouped = items.stream()
      .collect(Collectors.groupingBy(Item::kind, Collectors.mapping(Item::name, Collectors.toList())));

    return new StringJoiner(", ", "collections{", "}")
      .add("byName=" + byName.size())
      .add("prices=" + prices.size())
      .add("kinds=" + kinds.size())
      .add("perKind=" + perKind)
      .add("view=" + view.size())
      .add("queue=" + queue.peek())
      .add("deque=" + deque.peek())
      .add("listIterator=" + listIterator.hasNext())
      .add("first=" + first.isPresent())
      .add("grouped=" + grouped)
      .add("stream=" + stream.count())
      .add("locale=" + locale)
      .add("counted=" + Objects.toString(counter.get()))
      .toString();
  }

  String concurrency() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Callable<String> task = () -> "called#" + counter.incrementAndGet();
    Future<String> future = pool.submit(task);
    TimeUnit unit = TimeUnit.MILLISECONDS;
    try {
      return new StringJoiner(", ", "concurrency{", "}")
        .add("future=" + future.get())
        .add("unit=" + unit)
        .add("hits=" + hits.size())
        .toString();
    }
    finally {
      pool.shutdown();
    }
  }
}
