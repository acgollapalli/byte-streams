(ns byte-streams
  (:refer-clojure :exclude
    [object-array byte-array])
  (:require
    [manifold.stream :as s]
    [manifold.deferred :as d]
    [byte-streams.pushback-stream :as ps]
    [byte-streams.char-sequence :as cs]
    [byte-streams.utils :refer (fast-memoize)]
    [clojure.java.io :as io]
    [primitive-math :as p]
    [clj-tuple :refer (tuple)])
  (:import
    [byte_streams
     ByteBufferInputStream]
    [java.nio
     ByteBuffer
     DirectByteBuffer]
    [java.lang.reflect
     Array]
    [java.util
     PriorityQueue
     LinkedList]
    [java.util.concurrent
     ConcurrentHashMap]
    [java.io
     File
     FileOutputStream
     FileInputStream
     ByteArrayInputStream
     ByteArrayOutputStream
     PipedOutputStream
     PipedInputStream
     DataInputStream
     InputStream
     OutputStream
     RandomAccessFile
     Reader
     InputStreamReader
     BufferedReader]
    [java.nio.channels
     ReadableByteChannel
     WritableByteChannel
     FileChannel
     FileChannel$MapMode
     Channels
     Pipe]
    [java.nio.channels.spi
     AbstractSelectableChannel]))

;;; protocols

(defprotocol Closeable
  (close [_] "A protocol that is a superset of `java.io.Closeable`."))

(defprotocol ByteSource
  (take-bytes! [_ n options] "Takes `n` bytes from the byte source."))

(defprotocol ByteSink
  (send-bytes! [_ bytes options] "Puts `bytes` in the byte sink."))

;;;

(def src->dst->conversion (atom nil))
(defonce src->dst->transfer (atom nil))

(def ^:private ^:const object-array (class (clojure.core/object-array 0)))
(def ^:private ^:const byte-array (class (clojure.core/byte-array 0)))

(defn- protocol? [x]
  (and (map? x) (contains? x :on-interface)))

(declare seq-of stream-of)

(defn seq-of? [x]
  (and (seq? x)
    (symbol? (first x))
    (or
      (= 'seq-of (first x))
      (= #'seq-of (resolve (first x))))))

(defn stream-of? [x]
  (and (seq? x)
    (symbol? (first x))
    (or
      (= 'stream-of (first x))
      (= #'stream-of (resolve (first x))))))

(defn- abstract-type-descriptor [x]
  (cond

    (and (sequential? x) (= 'var (first x)))
    (abstract-type-descriptor (second x))

    (var? x)
    x

    (seq-of? x)
    (list 'list '(quote seq-of) (abstract-type-descriptor (second x)))

    (stream-of? x)
    (list 'list '(quote stream-of) (abstract-type-descriptor (second x)))

    :else
    (let [x (resolve x)
          x (if (var? x)
              @x
              x)]
      (if (protocol? x)
        (:var x)
        x))))

(defn seq-of [x]
  (list 'seq-of x))

(defn stream-of [x]
  (list 'stream-of x))

(defmacro def-conversion
  "Defines a conversion from one type to another."
  [[src dst :as conversion] params & body]
  (let [src' (abstract-type-descriptor src)
        dst' (abstract-type-descriptor dst)]
    `(swap! src->dst->conversion assoc-in [~src' ~dst']
       (with-meta
         (fn [~(with-meta (first params)
                 {:tag (when-not (var? src')
                         (if (= byte-array src')
                           'bytes
                           src))})
              ~(if-let [options (second params)]
                 options
                 `_#)]
           ~@body)
         (merge
           ~(meta conversion)
           {::conversion [~src' ~dst']})))))

(defmacro def-transfer
  "Defines a byte transfer from one type to another."
  [[src dst] params & body]
  (let [src' (abstract-type-descriptor src)
        dst' (abstract-type-descriptor dst)]
    `(swap! src->dst->transfer assoc-in [~src' ~dst']
       (fn [~(with-meta (first params) {:tag src'})
            ~(with-meta (second params) {:tag dst'})
            ~(if-let [options (get params 2)] options (gensym "options"))]
         ~@body))))

;;; convert

(def ^:private ^:dynamic *searched* #{})

(defn- searched? [k dst]
  (*searched* [k dst]))

(defn- cost [a b]
  (cond

    (= a b)
    0

    (= b (seq-of a))
    0

    (= b (stream-of a))
    0

    (and (stream-of? a) (seq-of? b) (= (second a) (second b)))
    1.0

    (and (seq-of? a) (stream-of? b) (= (second a) (second b)))
    1.0

    :else
    (-> (get-in @src->dst->conversion [a b]
          (when (or
                  (and (stream-of? a) (stream-of? b))
                  (and (seq-of? a) (seq-of? b)))
            (get-in @src->dst->conversion [(second a) (second b)])))
      meta
      (get :cost 1))))

(defn- class-satisfies? [protocol ^Class c]
  (boolean
    (or
      (.isAssignableFrom ^Class (:on-interface protocol) c)
      (some
        #(.isAssignableFrom ^Class % c)
        (keys (:impls protocol))))))

(defn- assignable? [a b]
  (let [a (if (var? a) @a a)
        b (if (var? b) @b b)]
    (cond
      (and (class? a) (class? b))
      (.isAssignableFrom ^Class b a)

      (and (protocol? b) (class? a))
      (class-satisfies? b a)

      (and (seq-of? a) (seq-of? b))
      (assignable? (second a) (second b))

      :else
      (= a b))))

(defn- equivalent-targets [x]
  (let [x (if (var? x) @x x)]
    (cond
      (seq-of? x)    (->> x second equivalent-targets (map seq-of))
      (stream-of? x) (->> x second equivalent-targets (map stream-of))
      (class? x)     [x]
      (protocol? x)  (conj (keys (get x :impls)) (:on-interface x))
      :else          [x])))

(def ^:private valid-conversions
  (fast-memoize
    (fn [src]
      (concat

        (->> @src->dst->conversion
          keys
          (filter (partial assignable? src))
          (mapcat #(map vector (repeat %) (keys (get @src->dst->conversion %))))
          (remove (comp nil? second)))

        (cond
          (stream-of? src)
          (concat
            [[src (seq-of (second src))]]
            (->> src
              second
              valid-conversions
              (remove
                (fn [[a b]]
                  (or (seq-of? b) (stream-of? b))))
              (map (partial map stream-of))))

          (seq-of? src)
          (concat
            [[src (stream-of (second src))]]
            (->> src
              second
              valid-conversions
              (remove
                (fn [[a b]]
                  (or (seq-of? b) (stream-of? b))))
              (map (partial map seq-of)))))))))

(deftype ConversionPath [path visited? cost]
  Comparable
  (compareTo [_ x]
    (let [cmp (compare cost (.cost ^ConversionPath x))]
      (if (zero? cmp)
        (compare (count path) (count (.path ^ConversionPath x)))
        cmp))))

(defn- conj-path [^ConversionPath p src dst]
  (ConversionPath.
    (conj (.path p) [src dst])
    (conj (.visited? p) dst)
    (+ (.cost p) (cost src dst))))

(defn conversion-path [src dst]
  (let [q (doto (PriorityQueue.)
            (.add (ConversionPath. [] #{src} 0)))
        dsts (equivalent-targets dst)]
    (loop []
      (when-let [^ConversionPath p (.poll q)]
        (let [curr (or (-> p .path last second) src)]
          (if (some #(assignable? curr %) dsts)
            (.path p)
            (do
              (doseq [[src dst] (->> curr
                                  valid-conversions
                                  (remove (fn [[src dst]] ((.visited? p) dst))))]
                (.add q (conj-path p src dst)))
              (recur))))))))

(defn- closeable-seq [s exhaustible? close-fn]
  (if (empty? s)
    (when exhaustible?
      (close-fn)
      nil)
    (reify

      Object
      (finalize [_]
        (close-fn))

      java.io.Closeable
      (close [_]
        (close-fn))

      clojure.lang.Sequential
      clojure.lang.ISeq
      clojure.lang.Seqable
      (seq [this] this)
      (cons [_ a]
        (closeable-seq (cons a s) exhaustible? close-fn))
      (next [this]
        (closeable-seq (next s) exhaustible? close-fn))
      (more [this]
        (let [rst (next this)]
          (if (empty? rst)
            '()
            rst)))
      (first [_]
        (first s))
      (equiv [a b]
        (= s b)))))

(let [m (ConcurrentHashMap.)]
  (defn- closeable? [x]
    (let [c (class x)
          v (.get m c)]
      (if (nil? v)
        (let [v (satisfies? Closeable x)]
          (.put m c v)
          v)
        v))))

(def ^:private converter
  (let [conversion-fn
        (fn [[a b :as a+b]]
          (or
            (get-in @src->dst->conversion a+b)

            ;; stream-of a -> seq-of a
            (and (stream-of? a) (seq-of? b) (= (second a) (second b))
              (fn [x _]
                (s/stream->seq x)))

            ;; seq-of a -> stream-of a
            (and (seq-of? a) (stream-of? b) (= (second a) (second b))
              (fn [x _]
                (s/->source x)))

            ;; a -> b -- seq-of a -> seq-of b
            (and (every? seq-of? a+b)
              (when-let [f (get-in @src->dst->conversion [(second a) (second b)])]
                (fn [x options]
                  (map #(f % options) x))))

            ;; a -> b -- stream-of a -> stream-of b
            (and (every? stream-of? a+b)
              (when-let [f (get-in @src->dst->conversion [(second a) (second b)])]
                (fn [x options]
                  (s/map #(f % options) x))))

            (throw
              (IllegalArgumentException.
                (str "We thought we could convert between " a " and " b ", but we can't.")))))]
    (fast-memoize
      (fn [src dst]
        (when-let [path (conversion-path src dst)]
          (condp = (count path)
            0 (fn [x _] x)

            1 (let [f (conversion-fn (first path))]
                (if (closeable? src)
                  (fn [x options]
                    (let [x' (f x options)]
                      (close x)
                      x'))
                  f))

            ;; multiple stages
            (let [fns (apply tuple (map conversion-fn path))]
              (fn [x options]
                (let [close-fns (LinkedList.)
                      result (reduce
                               (fn [x f]

                                 ;; keep track of everything that needs to be closed once the bytes are exhausted
                                 (when (closeable? x)
                                   (.add close-fns #(close x)))
                                 (f x options))
                               x
                               fns)]
                  (if-let [close-fn (when-not (.isEmpty close-fns)
                                      #(loop []
                                         (when-let [f (.poll close-fns)]
                                           (f)
                                           (recur))))]
                    (cond

                      (seq? result)
                      (closeable-seq result true close-fn)

                      (s/source? result)
                      (do
                        (s/on-drained result close-fn)
                        result)

                      :else
                      (do
                        ;; we assume that if the end-result is closeable, it will take care of all the intermediate
                        ;; objects beneath it.  I think this is true as long as we're not doing multiple streaming
                        ;; reads, but this might need to be revisited.
                        (when-not (closeable? result)
                          (close-fn))
                        result))
                    result))))))))))

(defn type-descriptor
  "Returns a descriptor that can be used with `conversion-path`."
  [x]
  (cond
    (class? x)
    x

    (protocol? x)
    (:var x)

    (contains? @src->dst->conversion (class x))
    (class x)

    (or (sequential? x) (= object-array (class x)))
    (seq-of (type-descriptor (first x)))

    :else
    (class x)))

(defn convert
  "Converts `x`, if possible, into type `dst`, which can be either a class or protocol.  If no such conversion
   is possible, an IllegalArgumentException is thrown.  If `x` is a stream, then the `src` type must be explicitly specified.

   `options` is a map, whose available settings depend on what sort of transform is being performed:

   `chunk-size` - if a stream is being transformed into a sequence of discrete chunks, `:chunk-size` describes the
                  size of the chunks, which default to 4096 bytes.

   `encoding`   - if a string is being encoded or decoded, `:encoding` describes the charset that is used, which
                  defaults to 'UTF-8'

   `direct?`    - if a byte-buffer is being allocated, `:direct?` describes whether it should be a direct buffer,
                  defaulting to false"
  ([x dst]
     (convert x dst nil))
  ([x dst options]
     (cond

       (s/source? x)
       (let [src (get options :source-type)]
         (assert src "must specify `:source-type` when converting streams")
         (if-let [f (converter src dst)]
           (f x options)
           (throw (IllegalArgumentException. (str "don't know how to convert " src " into " dst)))))

       (not (or (nil? x) (and (sequential? x) (empty? x))))
       (let [src (or
                   (when (sequential? x)
                     (get options :source-type))
                   (type-descriptor x))]
         (if (or
               (= src dst)
               (and (class? src) (class? dst) (.isAssignableFrom ^Class dst src)))
           x
           (if-let [f (converter src dst)]
             (f x options)
             (throw (IllegalArgumentException.
                      (if (seq-of? src)
                        (str "Don't know how to convert a sequence of " (second src) " into " dst)
                        (str "Don't know how to convert " src " into " dst)))))))

       :else
       nil)))

(defn possible-conversions
  "Returns a list of all possible conversion targets from the initial value or class."
  [x]
  (let [sources (equivalent-targets (type-descriptor x))
        destinations (->> @src->dst->conversion
                       vals
                       (mapcat keys)
                       (concat (keys @src->dst->conversion))
                       distinct)]
    (->> destinations
      (concat
        (->> destinations
          (map #(when-not (seq-of? %) (seq-of %)))
          (remove nil?))
        (->> destinations
          (map #(when-not (stream-of? %) (stream-of %)))
          (remove nil?)))
      distinct
      (filter
        (fn [dst]
          (some
            #(conversion-path % dst)
            sources))))))

(let [memoized-cost (fast-memoize
                      (fn [src dst]
                        (->> (conversion-path src dst)
                          (map #(apply cost %))
                          (reduce +))))]
  (defn conversion-cost
    "Returns the estimated cost of converting the data `x` to the destination type `dst`."
    ^long [x dst]
    (memoized-cost (type-descriptor x) dst)))

(defn precache-conversions
  "Walk the graph of conversions, making all subsequent conversions reliably fast."
  []
  (->> @src->dst->conversion
    (mapcat #(map list (repeat %) (possible-conversions %)))
    distinct
    (map #(apply conversion-path %))
    dorun))

;;; transfer

(defn- default-transfer
  [source sink {:keys [chunk-size] :or {chunk-size 1024} :as options}]
  (loop []
    (when-let [b (take-bytes! source chunk-size options)]
      (send-bytes! sink b options)
      (recur))))

(def ^:private transfer-fn
  (fast-memoize
    (fn this [src dst]
      (let [[src' dst'] (->> @src->dst->transfer
                          keys
                          (map (fn [src']
                                 (and
                                   (conversion-path src src')
                                   (when-let [dst' (some
                                                     #(and (conversion-path dst %) %)
                                                     (keys (@src->dst->transfer src')))]
                                     [(conversion-path src src') [src' dst']]))))
                          (sort-by (comp count first))
                          first
                          second)]
        (cond

          (and src' dst')
          (let [f (get-in @src->dst->transfer [src' dst'])]
            (fn [source sink options]
              (let [source' (convert source src' options)
                    sink' (convert sink dst' options)]
                (f source' sink' options))))

          (and
            (conversion-path src #'ByteSource)
            (conversion-path dst #'ByteSink))
          (fn [source sink {:keys [close?] :or {close? true} :as options}]
            (let [source' (convert source #'ByteSource options)
                  sink' (convert sink #'ByteSink options)]
              (default-transfer source' sink' options)
              (when close?
                (doseq [x [source sink source' sink']]
                  (when (closeable? x)
                    (close x))))))

          :else
          nil)))))

;; for byte transfers
(defn transfer
  "Transfers, if possible, all bytes from `source` into `sink`.  If this cannot be accomplished, an IllegalArgumentException is
   thrown.

   `options` is a map whose available settings depends on the source and sink types:

   `chunk-size` - if a stream is being transformed into a sequence of discrete chunks, `:chunk-size` describes the
                  size of the chunks, which default to 4096 bytes.

   `encoding`   - if a string is being encoded or decoded, `:encoding` describes the charset that is used, which
                  defaults to 'UTF-8'

   `append?`    - if a file is being written to, `:append?` determines whether the bytes will overwrite the existing content
                  or be appended to the end of the file.  This defaults to true.

   `close?`     - whether the sink should be closed once the transfer is done, defaults to true."
  ([source sink]
     (transfer source sink nil))
  ([source sink options]
     (transfer source nil sink options))
  ([source source-type sink options]
     (if (s/source? source)

       (let [msg @(s/take! source ::none)
             s' (s/stream)]
         (when-not (identical? ::none msg)
           (let [src (stream-of (type-descriptor msg))
                 dst (type-descriptor sink)]
             (if-let [f (transfer-fn src dst)]
               (do
                 (s/put! s' msg)
                 (s/connect source s')
                 (f s' sink options))
               (throw (IllegalArgumentException. (str "Don't know how to transfer between a stream of " (second src) " to " dst)))))))

       (let [src (type-descriptor source)
             dst (type-descriptor sink)]
         (if-let [f (transfer-fn src dst)]
           (f source sink options)
           (if (seq-of? src)
             (throw (IllegalArgumentException. (str "Don't know how to transfer between a sequence of " (second src) " to " dst)))
             (throw (IllegalArgumentException. (str "Don't know how to transfer between " src " to " dst)))))))))

(def ^{:doc "Web-scale."} dev-null
  (reify ByteSink
    (send-bytes! [_ _ _])))

(defn optimized-transfer?
  "Returns true if an optimized transfer function exists for the given source and sink objects."
  [type-descriptor sink-type]
  (boolean (transfer-fn type-descriptor sink-type)))

;;; conversion definitions

(def-conversion ^{:cost 0} [(stream-of byte-array) InputStream]
  [s options]
  (let [ps (ps/pushback-stream (get options :buffer-size 65536))]
    (s/consume
      (fn [^bytes ary]
        (ps/put-array ps ary 0 (alength ary)))
      s)
    (s/on-drained s #(ps/close ps))
    (ps/->input-stream ps)))

(def-conversion ^{:cost 0} [(stream-of ByteBuffer) InputStream]
  [s options]
  (let [ps (ps/pushback-stream (get options :buffer-size 65536))]
    (s/consume
      (fn [buf]
        (ps/put-buffer ps buf))
      s)
    (s/on-drained s #(ps/close ps))
    (ps/->input-stream ps)))

;; byte-array => byte-buffer
(def-conversion ^{:cost 0} [byte-array ByteBuffer]
  [ary {:keys [direct?] :or {direct? false}}]
  (if direct?
    (let [len (Array/getLength ary)
          ^ByteBuffer buf (ByteBuffer/allocateDirect len)]
      (.put buf ary 0 len)
      (.position buf 0)
      buf)
    (ByteBuffer/wrap ary)))

;; byte-array => input-stream
(def-conversion ^{:cost 0} [byte-array InputStream]
  [ary]
  (ByteArrayInputStream. ary))

;; byte-buffer => input-stream
(def-conversion ^{:cost 0} [ByteBuffer InputStream]
  [buf]
  (ByteBufferInputStream. (.duplicate buf)))

;; byte-buffer => byte-array
(def-conversion [ByteBuffer byte-array]
  [buf]
  (if (.hasArray buf)
    (if (== (alength (.array buf)) (.remaining buf))
      (.array buf)
      (let [ary (clojure.core/byte-array (.remaining buf))]
        (doto buf
          .mark
          (.get ary 0 (.remaining buf))
          .reset)
        ary))
    (let [^bytes ary (Array/newInstance Byte/TYPE (.remaining buf))]
      (doto buf .mark (.get ary) .reset)
      ary)))

;; sequence of byte-buffers => byte-buffer
(def-conversion [(seq-of ByteBuffer) ByteBuffer]
  [bufs {:keys [direct?] :or {direct? false}}]
  (cond
    (empty? bufs)
    (ByteBuffer/allocate 0)

    (and (empty? (rest bufs)) (not (closeable? bufs)))
    (first bufs)

    :else
    (let [len (reduce + (map #(.remaining ^ByteBuffer %) bufs))
          buf (if direct?
                (ByteBuffer/allocateDirect len)
                (ByteBuffer/allocate len))]
      (doseq [^ByteBuffer b bufs]
        (.mark b)
        (.put buf b)
        (.reset b))
      (when (closeable? bufs)
        (close bufs))
      (.flip buf))))

;; byte-buffer => sequence of byte-buffers
(def-conversion ^{:cost 0} [ByteBuffer (seq-of ByteBuffer)]
  [buf {:keys [chunk-size]}]
  (if chunk-size
    (let [lim (.limit buf)
          indices (range (.position buf) lim chunk-size)]
      (map
        #(-> buf
           .duplicate
           (.position %)
           ^ByteBuffer (.limit (min lim (+ % chunk-size)))
           .slice)
        indices))
    [buf]))

;; channel => input-stream
(def-conversion ^{:cost 0} [ReadableByteChannel InputStream]
  [channel]
  (Channels/newInputStream channel))

;; channel => lazy-seq of byte-buffers
(def-conversion [ReadableByteChannel (seq-of ByteBuffer)]
  [channel {:keys [chunk-size direct?] :or {chunk-size 4096, direct? false} :as options}]
  (when (.isOpen channel)
    (lazy-seq
      (when-let [b (take-bytes! channel chunk-size options)]
        (cons b (convert channel (seq-of ByteBuffer) options))))))

;; input-stream => channel
(def-conversion ^{:cost 0} [InputStream ReadableByteChannel]
  [input-stream]
  (Channels/newChannel input-stream))

;; string => byte-array
(def-conversion ^{:cost 2} [String byte-array]
  [s {:keys [encoding] :or {encoding "UTF-8"}}]
  (.getBytes s ^String (name encoding)))

;; byte-array => string
(def-conversion ^{:cost 2} [byte-array String]
  [ary {:keys [encoding] :or {encoding "UTF-8"}}]
  (String. ^bytes ary (name encoding)))

;; lazy-seq of byte-buffers => channel
(def-conversion ^{:cost 1.5} [(seq-of ByteBuffer) ReadableByteChannel]
  [bufs]
  (let [pipe (Pipe/open)
        ^WritableByteChannel sink (.sink pipe)
        source (doto ^AbstractSelectableChannel (.source pipe)
                 (.configureBlocking true))]
    (future
      (try
        (loop [s bufs]
          (when (and (not (empty? s)) (.isOpen sink))
            (let [buf (.duplicate ^ByteBuffer (first s))]
              (.write sink buf)
              (recur (rest s)))))
        (finally
          (.close sink))))
    source))

(def-conversion ^{:cost 1.5} [(seq-of #'ByteSource) InputStream]
  [srcs options]
  (let [chunk-size (get options :chunk-size 65536)
        out (PipedOutputStream.)
        in (PipedInputStream. out chunk-size)]
    (future
      (try
        (loop [s srcs]
          (when-not (empty? s)
            (transfer (first s) out)
            (recur (rest s))))
        (finally
          (.close out))))
    in))

(def-conversion ^{:cost 2} [#'ByteSource byte-array]
  [src options]
  (let [os (ByteArrayOutputStream.)]
    (transfer src os)
    (.toByteArray os)))

;; generic byte-source => lazy char-sequence
(def-conversion ^{:cost 2} [#'ByteSource CharSequence]
  [source options]
  (cs/decode-byte-source
    #(let [bytes (take-bytes! source % options)]
       (convert bytes ByteBuffer options))
    #(when (closeable? source)
       (close source))
    options))

;; input-stream => reader
(def-conversion ^{:cost 1.5} [InputStream Reader]
  [input-stream {:keys [encoding] :or {encoding "UTF-8"}}]
  (BufferedReader. (InputStreamReader. input-stream ^String encoding)))

;; reader => char-sequence
(def-conversion ^{:cost 1.5} [Reader CharSequence]
  [reader {:keys [chunk-size] :or {chunk-size 2048}}]
  (let [ary (char-array chunk-size)
        sb (StringBuilder.)]
    (loop []
      (let [n (.read reader ary 0 chunk-size)]
        (if (pos? n)
          (do
            (.append sb ary 0 n)
            (recur))
          sb)))))

;; char-sequence => string
(def-conversion [CharSequence String]
  [char-sequence]
  (.toString char-sequence))

;; sequence of strings => string
(def-conversion [(seq-of String) String]
  [strings]
  (let [sb (StringBuilder.)]
    (doseq [s strings]
      (.append sb s))
    (.toString sb)))

;; file => readable-channel
(def-conversion ^{:cost 0} [File ReadableByteChannel]
  [file]
  (.getChannel (FileInputStream. file)))

;; file => writable-channel
(def-conversion ^{:cost 0} [File WritableByteChannel]
  [file {:keys [append?] :or {append? true}}]
  (.getChannel (FileOutputStream. file (boolean append?))))

(def-conversion ^{:cost 0} [File (seq-of ByteBuffer)]
  [file {:keys [chunk-size writable?] :or {chunk-size (int 2e9), writable? false}}]
  (let [^RandomAccessFile raf (RandomAccessFile. file (if writable? "rw" "r"))
        ^FileChannel fc (.getChannel raf)
        buf-seq (fn buf-seq [offset]
                  (when-not (<= (.size fc) offset)
                    (let [remaining (- (.size fc) offset)]
                      (lazy-seq
                        (cons
                          (.map fc
                            (if writable?
                              FileChannel$MapMode/READ_WRITE
                              FileChannel$MapMode/READ_ONLY)
                            offset
                            (min remaining chunk-size))
                          (buf-seq (+ offset chunk-size)))))))]
    (closeable-seq
      (buf-seq 0)
      false
      #(do
         (.close raf)
         (.close fc)))))

;; output-stream => writable-channel
(def-conversion ^{:cost 0} [OutputStream WritableByteChannel]
  [output-stream]
  (Channels/newChannel output-stream))

;; writable-channel => output-stream
(def-conversion ^{:cost 0} [WritableByteChannel OutputStream]
  [channel]
  (Channels/newOutputStream channel))

;;; def-transfers

(def-transfer [ReadableByteChannel File]
  [channel file {:keys [chunk-size] :or {chunk-size (int 1e7)} :as options}]
  (let [^FileChannel fc (convert file WritableByteChannel options)]
    (try
      (loop [idx 0]
        (let [n (.transferFrom fc channel idx chunk-size)]
          (when (pos? n)
            (recur (+ idx n)))))
      (finally
        (.force fc true)
        (.close fc)))))

(def-transfer [File WritableByteChannel]
  [file
   channel
   {:keys [chunk-size
           close?]
    :or {chunk-size (int 1e6)
         close? true}
    :as options}]
  (let [^FileChannel fc (convert file ReadableByteChannel options)]
    (try
      (loop [idx 0]
        (let [n (.transferTo fc idx chunk-size channel)]
          (when (pos? n)
            (recur (+ idx n)))))
      (finally
        (when close?
          (.close ^WritableByteChannel channel))
        (.close fc)))))

(def-transfer [InputStream OutputStream]
  [input-stream
   output-stream
   {:keys [chunk-size
           close?]
    :or {chunk-size 4096
         close? true}
    :as options}]
  (let [ary (clojure.core/byte-array chunk-size)]
    (try
      (loop []
        (let [n (.read ^InputStream input-stream ary)]
          (when (pos? n)
            (.write ^OutputStream output-stream ary 0 n)
            (recur))))
      (.flush ^OutputStream output-stream)
      (finally
        (.close ^InputStream input-stream)
        (when close?
          (.close ^OutputStream output-stream))))))

;;; protocol extensions

(extend-protocol ByteSink

  OutputStream
  (send-bytes! [this b _]
    (let [^OutputStream os this]
      (.write os ^bytes (convert b byte-array))))

  WritableByteChannel
  (send-bytes! [this b _]
    (let [^WritableByteChannel ch this]
      (.write ch ^ByteBuffer (convert b ByteBuffer)))))

(extend-protocol ByteSource

  InputStream
  (take-bytes! [this n _]
    (let [ary (clojure.core/byte-array n)
          n (long n)]
      (loop [idx 0]
        (if (== idx n)
          ary
          (let [read (.read this ary idx (long (- n idx)))]
            (if (== -1 read)
              (when (pos? idx)
                (let [ary' (clojure.core/byte-array idx)]
                  (System/arraycopy ary 0 ary' 0 idx)
                  ary'))
              (recur (long (+ idx read)))))))))

  ReadableByteChannel
  (take-bytes! [this n {:keys [direct?] :or {direct? false}}]
    (when (.isOpen this)
      (let [^ByteBuffer buf (if direct?
                              (ByteBuffer/allocateDirect n)
                              (ByteBuffer/allocate n))]
        (while
          (and
            (.isOpen this)
            (pos? (.read this buf))))

        (when (pos? (.position buf))
          (.flip buf)))))

  ByteBuffer
  (take-bytes! [this n _]
    (when (pos? (.remaining this))
      (let [n (min (.remaining this) n)
            buf (-> this
                  .duplicate
                  ^ByteBuffer (.limit (+ (.position this) n))
                  ^ByteBuffer (.slice)
                  (.order (.order this)))]
        (.position this (+ n (.position this)))
        buf))))

(extend-protocol Closeable

  java.io.Closeable
  (close [this] (.close this))

  )

;;; print-bytes

(let [special-character? (->> "' _-+=`~{}[]()\\/#@!?.,;\"" (map int) set)]
  (defn- readable-character? [x]
    (or
      (Character/isLetterOrDigit (int x))
      (special-character? (int x)))))

(defn print-bytes
  "Prints out the bytes in both hex and ASCII representations, 16 bytes per line."
  [bytes]
  (let [bufs (convert bytes (seq-of ByteBuffer) {:chunk-size 16})]
    (doseq [^ByteBuffer buf bufs]
      (let [s (convert (.duplicate buf) String {:encoding "ISO-8859-1"})
            bytes (repeatedly (min 16 (.remaining buf)) #(.get buf))
            padding (* 3 (- 16 (count bytes)))
            hex-format #(->> "%02X" (repeat %) (interpose " ") (apply str))]
        (println
          (apply format
            (str
              (hex-format (min 8 (count bytes)))
              "  "
              (hex-format (max 0 (- (count bytes) 8))))
            bytes)
          (apply str (repeat padding " "))
          "   "
          (->> s
            (map #(if (readable-character? %) % "."))
            (apply str)))))))

;;; to-* helpers

(defn ^ByteBuffer to-byte-buffer
  "Converts the object to a `java.nio.ByteBuffer`."
  ([x]
     (to-byte-buffer x nil))
  ([x options]
     (condp instance? x
       ByteBuffer x
       byte-array (ByteBuffer/wrap x)
       String (ByteBuffer/wrap (.getBytes ^String x (name (get options :encoding "UTF-8"))))
       (convert x ByteBuffer options))))

(defn to-byte-buffers
  "Converts the object to a sequence of `java.nio.ByteBuffer`."
  ([x]
     (to-byte-buffers x nil))
  ([x options]
     (convert x (seq-of ByteBuffer) options)))

(defn ^"[B" to-byte-array
  "Converts the object to a byte-array."
  ([x]
     (to-byte-array x nil))
  ([x options]
     (condp instance? x
       byte-array x
       String (.getBytes ^String x (name (get options :encoding "UTF-8")))
       (convert x byte-array options))))

(defn to-byte-arrays
  "Converts the object to a byte-array."
  ([x]
     (to-byte-array x nil))
  ([x options]
     (convert x (seq-of byte-array) options)))

(defn ^InputStream to-input-stream
  "Converts the object to a `java.io.InputStream`."
  ([x]
     (to-input-stream x nil))
  ([x options]
     (condp instance? x
       byte-array (ByteArrayInputStream. x)
       ByteBuffer (ByteBufferInputStream. x)
       (convert x InputStream options))))

(defn ^DataInputStream to-data-input-stream
  ([x]
     (to-data-input-stream x nil))
  ([x options]
     (if (instance? DataInputStream x)
       x
       (DataInputStream. (to-input-stream x)))))

(defn ^InputStream to-output-stream
  "Converts the object to a `java.io.OutputStream`."
  ([x]
     (to-output-stream x nil))
  ([x options]
     (convert x OutputStream options)))

(defn ^CharSequence to-char-sequence
  "Converts to the object to a `java.lang.CharSequence`."
  ([x]
     (to-char-sequence x nil))
  ([x options]
     (if (instance? CharSequence x)
       x
       (convert x CharSequence options))))

(defn ^ReadableByteChannel to-readable-channel
  "Converts the object to a `java.nio.ReadableByteChannel`"
  ([x]
     (to-readable-channel x nil))
  ([x options]
     (convert x ReadableByteChannel options)))

(defn ^String to-string
  "Converts the object to a string."
  ([x]
     (to-string x nil))
  ([x options]
     (condp instance? x
       String x
       byte-array (String. ^"[B" x ^String (get options :charset "UTF-8"))
       (convert x String options))))

(defn to-reader
  "Converts the object to a java.io.Reader."
  ([x]
     (to-reader x nil))
  ([x options]
     (convert x Reader options)))

(defn to-line-seq
  "Converts the object to a lazy sequence of newline-delimited strings."
  ([x]
     (to-line-seq x nil))
  ([x options]
     (let [^Reader reader (convert x Reader options)]
       (closeable-seq (line-seq (BufferedReader. reader)) true #(.close reader)))))

(defn to-byte-source
  "Converts the object to something that satisfies `ByteSource`."
  ([x]
     (to-byte-source x nil))
  ([x options]
     (convert x #'ByteSource options)))

(defn to-byte-sink
  "Converts the object to something that satisfies `ByteSink`."
  ([x]
     (to-byte-sink x nil))
  ([x options]
     (convert x #'ByteSink options)))

;;;

(defn- cmp-bufs
  ^long [^ByteBuffer a' ^ByteBuffer b']
  (let [diff (p/- (.remaining a') (.remaining b'))
        sign (long (if (pos? diff) -1 1))
        a (if (pos? diff) b' a')
        b (if (pos? diff) a' b')
        limit (p/>> (.remaining a) 2)
        a-offset (.position a)
        b-offset (.position b)]
    (let [cmp (loop [idx 0]
                (if (p/>= idx limit)
                  0
                  (let [cmp (p/-
                              (p/int->uint (.getInt a (p/+ idx a-offset)))
                              (p/int->uint (.getInt b (p/+ idx b-offset))))]
                    (if (p/== 0 cmp)
                      (recur (p/+ idx 4))
                      (p/* sign cmp)))))]
      (if (p/== 0 (long cmp))
        (let [limit' (.remaining a)]
          (loop [idx limit]
            (if (p/>= idx limit')
              diff
              (let [cmp (p/-
                          (p/byte->ubyte (.get a (p/+ idx a-offset)))
                          (p/byte->ubyte (.get b (p/+ idx b-offset))))]
                (if (p/== 0 cmp)
                  (recur (p/inc idx))
                  (p/* sign cmp))))))
        cmp))))

(defn compare-bytes
  "Returns a comparison result for two byte streams."
  ^long [a b]
  (if (and
        (or
          (instance? byte-array a)
          (instance? ByteBuffer a)
          (instance? String a))
        (or
          (instance? byte-array b)
          (instance? ByteBuffer b)
          (instance? String b)))
    (cmp-bufs (to-byte-buffer a) (to-byte-buffer b))
    (loop [a (to-byte-buffers a), b (to-byte-buffers b)]
      (cond
        (empty? a)
        (if (empty? b) 0 -1)

        (empty? b)
        1

        :else
        (let [cmp (cmp-bufs (first a) (first b))]
          (if (p/== 0 cmp)
            (recur (rest a) (rest b))
            cmp))))))

(defn bytes=
  "Returns true if the two byte streams are equivalent."
  [a b]
  (p/== 0 (compare-bytes a b)))
