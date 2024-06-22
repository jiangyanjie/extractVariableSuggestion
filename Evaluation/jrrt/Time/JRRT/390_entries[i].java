package org.joda.time.convert;

class ConverterSet  {
  final private Converter[] iConverters;
  private Entry[] iSelectEntries;
  ConverterSet(Converter[] converters) {
    super();
    iConverters = converters;
    iSelectEntries = new Entry[1 << 4];
  }
  Converter select(Class<?> type) throws IllegalStateException {
    Entry[] entries = iSelectEntries;
    int length = entries.length;
    int index = type == null ? 0 : type.hashCode() & (length - 1);
    Entry e;
    while((e = entries[index]) != null){
      if(e.iType == type) {
        return e.iConverter;
      }
      if(++index >= length) {
        index = 0;
      }
    }
    Converter converter = selectSlow(this, type);
    e = new Entry(type, converter);
    entries = (Entry[])entries.clone();
    entries[index] = e;
    for(int i = 0; i < length; i++) {
      Entry var_390 = entries[i];
      if(var_390 == null) {
        iSelectEntries = entries;
        return converter;
      }
    }
    int newLength = length << 1;
    Entry[] newEntries = new Entry[newLength];
    for(int i = 0; i < length; i++) {
      e = entries[i];
      type = e.iType;
      index = type == null ? 0 : type.hashCode() & (newLength - 1);
      while(newEntries[index] != null){
        if(++index >= newLength) {
          index = 0;
        }
      }
      newEntries[index] = e;
    }
    iSelectEntries = newEntries;
    return converter;
  }
  private static Converter selectSlow(ConverterSet set, Class<?> type) {
    Converter[] converters = set.iConverters;
    int length = converters.length;
    Converter converter;
    for(int i = length; --i >= 0; ) {
      converter = converters[i];
      Class<?> supportedType = converter.getSupportedType();
      if(supportedType == type) {
        return converter;
      }
      if(supportedType == null || (type != null && !supportedType.isAssignableFrom(type))) {
        set = set.remove(i, null);
        converters = set.iConverters;
        length = converters.length;
      }
    }
    if(type == null || length == 0) {
      return null;
    }
    if(length == 1) {
      return converters[0];
    }
    for(int i = length; --i >= 0; ) {
      converter = converters[i];
      Class<?> supportedType = converter.getSupportedType();
      for(int j = length; --j >= 0; ) {
        if(j != i && converters[j].getSupportedType().isAssignableFrom(supportedType)) {
          set = set.remove(j, null);
          converters = set.iConverters;
          length = converters.length;
          i = length - 1;
        }
      }
    }
    if(length == 1) {
      return converters[0];
    }
    StringBuilder msg = new StringBuilder();
    msg.append("Unable to find best converter for type \"");
    msg.append(type.getName());
    msg.append("\" from remaining set: ");
    for(int i = 0; i < length; i++) {
      converter = converters[i];
      Class<?> supportedType = converter.getSupportedType();
      msg.append(converter.getClass().getName());
      msg.append('[');
      msg.append(supportedType == null ? null : supportedType.getName());
      msg.append("], ");
    }
    throw new IllegalStateException(msg.toString());
  }
  ConverterSet add(Converter converter, Converter[] removed) {
    Converter[] converters = iConverters;
    int length = converters.length;
    for(int i = 0; i < length; i++) {
      Converter existing = converters[i];
      if(converter.equals(existing)) {
        if(removed != null) {
          removed[0] = null;
        }
        return this;
      }
      if(converter.getSupportedType() == existing.getSupportedType()) {
        Converter[] copy = new Converter[length];
        for(int j = 0; j < length; j++) {
          if(j != i) {
            copy[j] = converters[j];
          }
          else {
            copy[j] = converter;
          }
        }
        if(removed != null) {
          removed[0] = existing;
        }
        return new ConverterSet(copy);
      }
    }
    Converter[] copy = new Converter[length + 1];
    System.arraycopy(converters, 0, copy, 0, length);
    copy[length] = converter;
    if(removed != null) {
      removed[0] = null;
    }
    return new ConverterSet(copy);
  }
  ConverterSet remove(final int index, Converter[] removed) {
    Converter[] converters = iConverters;
    int length = converters.length;
    if(index >= length) {
      throw new IndexOutOfBoundsException();
    }
    if(removed != null) {
      removed[0] = converters[index];
    }
    Converter[] copy = new Converter[length - 1];
    int j = 0;
    for(int i = 0; i < length; i++) {
      if(i != index) {
        copy[j++] = converters[i];
      }
    }
    return new ConverterSet(copy);
  }
  ConverterSet remove(Converter converter, Converter[] removed) {
    Converter[] converters = iConverters;
    int length = converters.length;
    for(int i = 0; i < length; i++) {
      if(converter.equals(converters[i])) {
        return remove(i, removed);
      }
    }
    if(removed != null) {
      removed[0] = null;
    }
    return this;
  }
  int size() {
    return iConverters.length;
  }
  void copyInto(Converter[] converters) {
    System.arraycopy(iConverters, 0, converters, 0, iConverters.length);
  }
  
  static class Entry  {
    final Class<?> iType;
    final Converter iConverter;
    Entry(Class<?> type, Converter converter) {
      super();
      iType = type;
      iConverter = converter;
    }
  }
}