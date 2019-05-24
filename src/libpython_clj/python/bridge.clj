(ns libpython-clj.python.bridge
  "Bridging classes to allow python and java to intermix."
  (:require [libpython-clj.jna :as libpy]
            [libpython-clj.jna.base :as libpy-base]
            [libpython-clj.python.interpreter
             :refer
             [with-gil
              with-interpreter
              ensure-bound-interpreter
              check-error-throw]]
            [libpython-clj.python.object
             :refer
             [py-type-keyword
              copy-to-jvm
              copy-to-python
              py-string->string
              has-attr?
              get-attr
              stringable?
              stringable
              incref
              py-dir
              incref-wrap-pyobject
              wrap-pyobject
              python->jvm-iterable
              ->py-list
              ->py-tuple
              ->py-dict
              ->py-string]]
            [libpython-clj.python.interop :as pyinterop
             :refer
             [expose-bridge-to-python!
              pybridge->bridge
              create-function]]
            [clojure.stacktrace :as st])
  (:import [java.util Map RandomAccess List Map$Entry Iterator]
           [clojure.lang IFn]
           [tech.v2.datatype ObjectReader ObjectWriter ObjectMutable
            ObjectIter]
           [com.sun.jna Pointer]
           [libpython_clj.jna JVMBridge
            CFunction$KeyWordFunction
            CFunction$TupleFunction
            CFunction$NoArgFunction]))


(def bridgeable-python-type-set
  #{:list :dict :tuple :string :int :float})


(defn bridgeable-python-type?
  [pyobj]
  (with-gil
    (or (-> (py-type-keyword pyobj)
            bridgeable-python-type-set
            boolean)
        (has-attr? pyobj "__iter__")
        (has-attr? pyobj "__getitem__"))))


(defn bridgeable-jvm-type?
  [jvm-obj]
  (or (number? jvm-obj)
      (stringable? jvm-obj)
      (instance? jvm-obj RandomAccess)
      (instance? jvm-obj Map)
      (instance? jvm-obj Iterable)))


(declare python->jvm jvm->python)


(defn check-py-method-return
  [^long retval]
  (when-not (= 0 retval)
    (check-error-throw)))


(defn python-list->jvm
  [pyobj]
  (when-not (= :list (py-type-keyword pyobj))
    (throw (ex-info ("Object is not a list: %s" (py-type-keyword pyobj))
                    {})))
  (with-gil
    (let [interpreter (ensure-bound-interpreter)]
      (reify
        libpy-base/PToPyObjectPtr
        (->py-object-ptr [item] pyobj)

        ObjectReader
        (lsize [reader]
          (with-interpreter interpreter
            (libpy/PyList_Size pyobj)))
        (read [reader idx]
          (with-interpreter interpreter
            (-> (libpy/PyList_GetItem pyobj idx)
                incref-wrap-pyobject
                python->jvm)))
        ObjectWriter
        (write [writer idx value]
          (with-interpreter interpreter
            (->> (jvm->python value)
                 incref
                 (libpy/PyList_SetItem pyobj idx)
                 (check-py-method-return))))
        ObjectMutable
        (insert [mutable idx value]
          (with-interpreter interpreter
            (->> (jvm->python value)
                 incref
                 (libpy/PyList_Insert pyobj idx)
                 check-py-method-return)))
        (append [mutable value]
          (with-interpreter interpreter
            (->> (jvm->python value)
                 incref
                 (libpy/PyList_Append pyobj)
                 (check-py-method-return))))))))


(defn python-tuple->jvm
  [pyobj]
  (when-not (= :tuple (py-type-keyword pyobj))
    (throw (ex-info ("Object is not a tuple: %s" (py-type-keyword pyobj))
                    {})))
  (with-gil
    (let [interpreter (ensure-bound-interpreter)
          n-elems (libpy/PyObject_Length pyobj)]
      (reify ObjectReader
        libpy-base/PToPyObjectPtr
        (->py-object-ptr [item] pyobj)
        (lsize [reader] n-elems)
        (read [reader idx]
          (with-interpreter interpreter
            (-> (libpy/PyTuple_GetItem pyobj idx)
                incref-wrap-pyobject
                (python->jvm))))))))


(defn check-pybool-return
  [^long retval]
  (cond
    (> retval 0) true
    (= retval 0) false
    :else
    (check-error-throw)))


(defn checknil
  [message item]
  (when-not item
    (throw (ex-info message {})))
  item)


(defn- get-dict-item
  [pyobj obj-key]
  (->> obj-key
       (jvm->python)
       (libpy/PyDict_GetItem pyobj)))


(defn- set-dict-item
  [pyobj obj-key obj-val]
  (let [obj-val (jvm->python obj-val)]
    (let [obj-key (jvm->python obj-key)]
      (libpy/PyDict_SetItem pyobj obj-key obj-val))))


(defn- remove-dict-item
  [pyobj k]
  (libpy/PyDict_DelItem pyobj (jvm->python k)))


(defn- dict-mapentry-items
  [pyobj]
  (->> (libpy/PyDict_Items pyobj)
       (wrap-pyobject)
       (python->jvm)
       (map (fn [[k v :as tuple]]
              (reify Map$Entry
                (getKey [this] k)
                (getValue [this] v)
                (hashCode [this] (.hashCode tuple))
                (equals [this o]
                  (.equals tuple o)))))))


(defn python-dict->jvm
  [pyobj]
  (with-gil
    (when-not (= :dict (py-type-keyword pyobj))
      (throw (ex-info ("Function called on incorrect type: %s" (py-type-keyword pyobj))
                      {})))
    ;;This is going to be a motherfucker to get right thanks to the way instain mother of
    ;;the java map interface.
    (let [n-elems (libpy/PyDict_Size pyobj)
          interpreter (ensure-bound-interpreter)]

      (reify
        libpy-base/PToPyObjectPtr
        (->py-object-ptr [item] pyobj)
        ;;oh fuck...
        Map
        (clear [this]
          (with-interpreter interpreter
            (libpy/PyDict_Clear pyobj)))
        (containsKey [this obj-key]
          (with-interpreter interpreter
            (->> (jvm->python obj-key)
                 (libpy/PyDict_Contains pyobj)
                 check-pybool-return
                 boolean)))
        (entrySet [this]
          (with-interpreter interpreter
            (->> (dict-mapentry-items pyobj)
                 set)))
        (get [this obj-key]
          (with-interpreter interpreter
            (->> (get-dict-item pyobj obj-key)
                 (checknil "Failed to get item from dictionary")
                 (incref-wrap-pyobject)
                 (python->jvm))))
        (getOrDefault [this obj-key obj-default-value]
          (with-interpreter interpreter
            (->> (get-dict-item pyobj obj-key)
                 (#(fn [dict-value]
                     (if dict-value
                       (-> dict-value
                           (incref-wrap-pyobject)
                           (python->jvm))
                       obj-default-value))))))
        (isEmpty [this]
          (with-interpreter interpreter
            (= (libpy/PyDict_Size pyobj) 0)))

        (keySet [this]
          (with-interpreter interpreter
            (->> (libpy/PyDict_Keys pyobj)
                 (python->jvm-iterable python->jvm)
                 set)))

        (put [this k v]
          (with-interpreter interpreter
            (-> (set-dict-item pyobj k v)
                check-pybool-return)
            v))

        (remove [this k]
          (with-interpreter interpreter
            (when-let [existing (get-dict-item pyobj k)]
              (-> (remove-dict-item pyobj k)
                  check-pybool-return)
              existing)))

        (size [this]
          (with-interpreter interpreter
            (libpy/PyDict_Size pyobj)))

        (values [this]
          (with-interpreter interpreter
            (-> (libpy/PyDict_Values pyobj)
                wrap-pyobject
                python->jvm)))
        Iterable
        (iterator [this]
          (with-interpreter interpreter
            (-> (dict-mapentry-items pyobj)
                (.iterator))))))))


(defn python-bridge->jvm
  [pyobj]
  (when-not (= :jvm-bridge (py-type-keyword pyobj))
    (throw (ex-info "Object isn't a bridge object." {})))
  (let [real-bridge (pybridge->bridge pyobj)]
    (.wrappedObject real-bridge)))


(defn python-callable->jvm
  [pyobj]
  (with-gil
    (let [interpreter (ensure-bound-interpreter)]
      (reify
        libpy-base/PToPyObjectPtr
        (->py-object-ptr [item] pyobj)
        IFn
        ;;uggh
        (invoke [this]
          (with-interpreter interpreter
            (-> (libpy/PyObject_CallObject pyobj nil)
                wrap-pyobject
                python->jvm)))

        (invoke [this arg0]
          (with-interpreter interpreter
            (-> (libpy/PyObject_CallObject pyobj (->py-tuple [arg0]))
                wrap-pyobject
                python->jvm)))

        (invoke [this arg0 arg1]
          (with-interpreter interpreter
            (-> (libpy/PyObject_CallObject pyobj (->py-tuple [arg0 arg1]))
                wrap-pyobject
                python->jvm)))

        (invoke [this arg0 arg1 arg2]
          (with-interpreter interpreter
            (-> (libpy/PyObject_CallObject pyobj (->py-tuple [arg0 arg1 arg2]))
                wrap-pyobject
                python->jvm)))

        (invoke [this arg0 arg1 arg2 arg3]
          (with-interpreter interpreter
            (-> (libpy/PyObject_CallObject pyobj (->py-tuple [arg0 arg1 arg2 arg3]))
                wrap-pyobject
                python->jvm)))


        (invoke [this arg0 arg1 arg2 arg3 arg4]
          (with-interpreter interpreter
            (-> (libpy/PyObject_CallObject pyobj (->py-tuple [arg0 arg1 arg2 arg3 arg4]))
                wrap-pyobject
                python->jvm)))

        (applyTo [this arglist]
          (with-interpreter interpreter
            (-> (libpy/PyObject_CallObject pyobj (->py-tuple arglist))
                wrap-pyobject
                python->jvm)))))))


(defn python-iterable->jvm
  [pyobj]
  (python->jvm-iterable pyobj python->jvm))


(defn python->jvm
  "Attempts to build a jvm bridge that 'hides' the python type.  This bridge is lazy and
  noncaching so use it wisely; it may be better to just copy the type once into the JVM.
  Returns map of {:type :value} if the type isn't bridgeable.  Atomic objects
  (string,number) are copied always.  Bridging is recursive so any subtypes are also
  bridged if possible or represented by a hashmap of {:type :value} if not."
  [pyobj]
  (when pyobj
    (with-gil
      (let [obj-type (py-type-keyword pyobj)]
        (case obj-type
          :non-type nil
          :int (libpy/PyLong_AsLong pyobj)
          :float (libpy/PyFloat_AsDouble pyobj)
          :str (py-string->string pyobj)
          :list (python-list->jvm pyobj)
          :tuple (python-tuple->jvm pyobj)
          :dict (python-dict->jvm pyobj)
          :jvm-bridge (python-bridge->jvm pyobj)
          (cond
            (= 1 (libpy/PyCallable_Check pyobj))
            (python-callable->jvm pyobj)
            (has-attr? pyobj "__iter__")
            (python-iterable->jvm pyobj)
            :else
            (throw (ex-info (format "Unable to bridge type: %s"
                                    (py-type-keyword pyobj))
                            {}))))))))

(defmacro wrap-jvm-context
  [& body]
  `(try
     ~@body
     (catch Throwable e#
       (libpy/PyErr_SetString
        libpy/PyExc_Exception
        (format "%s:%s" (str e#)
                (with-out-str
                  (st/print-stack-trace e#)))))))

(defmacro impl-tuple-function
  [& body]
  `(reify CFunction$TupleFunction
     (pyinvoke [this# ~'self ~'args]
       (wrap-jvm-context
        (-> (let [~'args (python->jvm ~'args)]
              ~@body)
            jvm->python)))))


(defn jvm-fn->iface
  [jvm-fn]
  (impl-tuple-function
   (apply jvm-fn args)))


(defn jvm-fn->python
  [jvm-fn]
  (-> (jvm-fn->iface jvm-fn)
      create-function))


(defn create-bridge-from-att-map
  [src-item att-map]
  (let [interpreter (ensure-bound-interpreter)
        dir-data (->> (keys att-map)
                      sort
                      (into-array String))
        bridge
        (reify JVMBridge
          (getAttr [bridge att-name]
            (if-let [retval (get att-map att-name)]
              (incref retval)
              (libpy/Py_None)))
          (setAttr [bridge att-name att-value]
            (throw (ex-info "Cannot set attributes" {})))
          (dir [bridge] dir-data)
          (interpreter [bridge] interpreter)
          (wrappedObject [bridge] src-item))]
    (expose-bridge-to-python! bridge)))


(defn jvm-iterator->python
  ^Pointer [^Iterator item]
  (with-gil nil
    (let [att-map
          {"__next__" (jvm-fn->python
                       #(when (.hasNext item)
                          (.nextItem item)))}]
      (create-bridge-from-att-map item att-map))))


(defn jvm-map->python
  ^Pointer [^Map jvm-data]
  (with-gil
    (let [att-map
          {"__contains__" (jvm-fn->python #(.contains-key jvm-data %))
           "__eq__" (jvm-fn->python #(.equals jvm-data %))
           "__getitem__" (jvm-fn->python #(.get jvm-data %))
           "__setitem__" (jvm-fn->python #(.put jvm-data %1 %2))
           "__hash__" (jvm-fn->python #(.hashCode jvm-data))
           "__iter__" (jvm-fn->python #(.iterator jvm-data))
           "__len__" (jvm-fn->python #(.size jvm-data))
           "__str__" (jvm-fn->python #(.toString jvm-data))
           "clear" (jvm-fn->python #(.clear jvm-data))
           "keys" (jvm-fn->python #(seq (.keySet jvm-data)))
           "values" (jvm-fn->python #(seq (.values jvm-data)))}]
      (create-bridge-from-att-map jvm-data att-map))))


(defn jvm-iterable->python
  ^Pointer [^Iterable jvm-data]
  (with-gil
    (let [att-map
          {"__iter__" (jvm-fn->python #(.iterator jvm-data))
           "__eq__" (jvm-fn->python #(.equals jvm-data %))
           "__hash__" (jvm-fn->python #(.hashCode jvm-data))}]
      (create-bridge-from-att-map jvm-data att-map))))


(defn jvm->python
  [jvm-obj]
  (with-gil nil
    (if jvm-obj
      (cond
        (number? jvm-obj)
        (copy-to-python jvm-obj)
        (boolean? jvm-obj)
        (copy-to-python jvm-obj)
        (stringable? jvm-obj)
        (copy-to-python (stringable jvm-obj))
        (instance? Map jvm-obj)
        (jvm-map->python jvm-obj)
        (instance? Iterable jvm-obj)
        (jvm-iterable->python jvm-obj)
        (instance? Iterator jvm-obj)
        (jvm-iterator->python jvm-obj)
        :else
        (throw (ex-info "not implemented yet" {})))
      (libpy/Py_None))))
