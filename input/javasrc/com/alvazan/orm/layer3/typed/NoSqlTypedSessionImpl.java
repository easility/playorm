package com.alvazan.orm.layer3.typed;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import com.alvazan.orm.api.z3api.NoSqlTypedSession;
import com.alvazan.orm.api.z3api.QueryResult;
import com.alvazan.orm.api.z5api.IndexColumnInfo;
import com.alvazan.orm.api.z5api.IndexPoint;
import com.alvazan.orm.api.z5api.NoSqlSession;
import com.alvazan.orm.api.z5api.QueryParser;
import com.alvazan.orm.api.z5api.SpiMetaQuery;
import com.alvazan.orm.api.z5api.SpiQueryAdapter;
import com.alvazan.orm.api.z8spi.KeyValue;
import com.alvazan.orm.api.z8spi.MetaLoader;
import com.alvazan.orm.api.z8spi.Row;
import com.alvazan.orm.api.z8spi.ScanInfo;
import com.alvazan.orm.api.z8spi.action.Column;
import com.alvazan.orm.api.z8spi.action.IndexColumn;
import com.alvazan.orm.api.z8spi.iter.AbstractCursor;
import com.alvazan.orm.api.z8spi.iter.Cursor;
import com.alvazan.orm.api.z8spi.iter.DirectCursor;
import com.alvazan.orm.api.z8spi.iter.IterToVirtual;
import com.alvazan.orm.api.z8spi.meta.DboColumnIdMeta;
import com.alvazan.orm.api.z8spi.meta.DboColumnMeta;
import com.alvazan.orm.api.z8spi.meta.DboTableMeta;
import com.alvazan.orm.api.z8spi.meta.IndexData;
import com.alvazan.orm.api.z8spi.meta.NoSqlTypedRowProxy;
import com.alvazan.orm.api.z8spi.meta.RowToPersist;
import com.alvazan.orm.api.z8spi.meta.TypedColumn;
import com.alvazan.orm.api.z8spi.meta.TypedRow;
import com.alvazan.orm.api.z8spi.meta.ViewInfo;


public class NoSqlTypedSessionImpl implements NoSqlTypedSession {

	@Inject
	private QueryParser noSqlSessionFactory;
	@Inject
	private CachedMeta cachedMeta;
	
	private MetaLoader mgr;
	private NoSqlSession session;
	
	/**
	 * NOTE: This must be here so that if you get TypedSession from the NoSqlEntitManager, he will
	 * have the same session object and flush flushes both typed and ORM data at the same time!!!
	 * To be removed eventually
	 * @param s
	 */
	public void setInformation(NoSqlSession s, MetaLoader mgr) {
		this.session = s;
		this.mgr = mgr;
	}
	
	@Override
	public NoSqlSession getRawSession() {
		return session;
	}

	@Override
	public void put(String colFamily, TypedRow typedRow) {
		DboTableMeta metaClass = cachedMeta.getMeta(colFamily);
		if(metaClass == null)
			throw new IllegalArgumentException("DboTableMeta for colFamily="+colFamily+" was not found");

		RowToPersist row = metaClass.translateToRow(typedRow);
		
		//This is if we need to be removing columns from the row that represents the entity in a oneToMany or ManyToMany
		//as the entity.accounts may have removed one of the accounts!!!
		if(row.hasRemoves())
			session.remove(metaClass, row.getKey(), row.getColumnNamesToRemove());
		
		//NOW for index removals if any indexed values change of the entity, we remove from the index
		for(IndexData ind : row.getIndexToRemove()) {
			session.removeFromIndex(metaClass, ind.getColumnFamilyName(), ind.getRowKeyBytes(), ind.getIndexColumn());
		}
		
		//NOW for index adds, if it is a new entity or if values change, we persist those values
		for(IndexData ind : row.getIndexToAdd()) {
			session.persistIndex(metaClass, ind.getColumnFamilyName(), ind.getRowKeyBytes(), ind.getIndexColumn());
		}
		
		byte[] virtualKey = row.getVirtualKey();
		List<Column> cols = row.getColumns();
		session.put(metaClass, virtualKey, cols);
	}
	
	@Override
	public void removeIndexPoint(IndexPoint pt, String partitionBy, String partitionId) {
		DboColumnMeta colMeta = pt.getColumnMeta();
		ScanInfo info = ScanInfo.createScanInfo(colMeta, partitionBy, partitionId);
		byte[] rowKey = info.getRowKey();
		String indColFamily = info.getIndexColFamily();
		DboTableMeta cf = info.getEntityColFamily();
		
		IndexColumn col = new IndexColumn();
		col.setIndexedValue(pt.getRawIndexedValue());
		col.setPrimaryKey(pt.getRawKey());
		session.removeFromIndex(cf, indColFamily, rowKey, col);
	}
	public void addIndexPoint(IndexPoint pt, String partitionBy, String partitionId) {
		DboColumnMeta colMeta = pt.getColumnMeta();
		ScanInfo info = ScanInfo.createScanInfo(colMeta, partitionBy, partitionId);
		byte[] rowKey = info.getRowKey();
		String indColFamily = info.getIndexColFamily();
		DboTableMeta cf = info.getEntityColFamily();
		
		IndexColumn col = new IndexColumn();
		col.setIndexedValue(pt.getRawIndexedValue());
		col.setPrimaryKey(pt.getRawKey());
		session.persistIndex(cf, indColFamily, rowKey, col);
	}
	
	@Override
	public void remove(String colFamily, TypedRow row) {
		DboTableMeta metaDbo = cachedMeta.getMeta(colFamily);
		if(metaDbo == null)
			throw new IllegalArgumentException("DboTableMeta for colFamily="+colFamily+" was not found");
		
		TypedRow proxy = row;
		Object rowKey = row.getRowKey();
		DboColumnIdMeta idMeta = metaDbo.getIdColumnMeta();
		byte[] byteKey = idMeta.convertToStorage2(rowKey);
		byte[] virtualKey = idMeta.formVirtRowKey(byteKey);
		if(!metaDbo.hasIndexedField()) {
			session.remove(metaDbo, virtualKey);
			return;
		} else if(!(row instanceof NoSqlTypedRowProxy)) {
			//then we don't have the database information for indexes so we need to read from the database
			proxy = find(metaDbo.getColumnFamily(), rowKey);
		}
		
		List<IndexData> indexToRemove = metaDbo.findIndexRemoves((NoSqlTypedRowProxy)proxy, byteKey);
		
		//REMOVE EVERYTHING HERE, we are probably removing extra and could optimize this later
		for(IndexData ind : indexToRemove) {
			session.removeFromIndex(metaDbo, ind.getColumnFamilyName(), ind.getRowKeyBytes(), ind.getIndexColumn());
		}
		
		session.remove(metaDbo, virtualKey);
	}
	
	@Override
	public TypedRow find(String cf, Object id) {
		List<Object> keys = new ArrayList<Object>();
		keys.add(id);
		List<KeyValue<TypedRow>> rows = findAllList(cf, keys);
		return rows.get(0).getValue();
	}
	
	@Override
	public Cursor<KeyValue<TypedRow>> createFindCursor(String colFamily, Iterable<Object> keys, int batchSize) {
		if(keys == null)
			throw new IllegalArgumentException("keys list cannot be null");
		DboTableMeta meta = cachedMeta.getMeta(colFamily);
		if(meta == null)
			throw new IllegalArgumentException("Meta for columnfamily="+colFamily+" was not found");
		DboColumnMeta idMeta = meta.getIdColumnMeta();
		Iterable<byte[]> noSqlKeys = new IterableTypedProxy<Object>(idMeta, keys);
		return findAllImpl2(meta, keys, noSqlKeys, null, batchSize);
	}

	<T> AbstractCursor<KeyValue<TypedRow>> findAllImpl2(DboTableMeta meta, Iterable<T> keys, Iterable<byte[]> noSqlKeys, String query, int batchSize) {
		
		Iterable<byte[]> virtKeys = new IterToVirtual(meta, noSqlKeys);
		//NOTE: It is WAY more efficient to find ALL keys at once then it is to
		//find one at a time.  You would rather have 1 find than 1000 if network latency was 1 ms ;).
		AbstractCursor<KeyValue<Row>> rows2 = session.find(meta, virtKeys, true, false, batchSize);
		if(keys != null)
			return new CursorTypedResp<T>(meta, keys, rows2);
		else
			return new CursorTypedResp<T>(meta, rows2, query);
	}

	@Override
	public List<KeyValue<TypedRow>> findAllList(String colFamily, Iterable<Object> keys) {
		List<KeyValue<TypedRow>> rows = new ArrayList<KeyValue<TypedRow>>();
		Cursor<KeyValue<TypedRow>> iter = createFindCursor(colFamily, keys, 500);
		while(iter.next()) {
			KeyValue<TypedRow> keyValue = iter.getCurrent();
			rows.add(keyValue);
		}

		return rows;
	}
	
	@Override
	public void flush() {
		session.flush();
	}

	@Override
	public Cursor<IndexPoint> indexView(String columnFamily, String column,
			String partitionBy, String partitionId) {
		DboTableMeta meta = cachedMeta.getMeta(columnFamily);
		if(meta == null)
			throw new IllegalArgumentException("columnFamily="+columnFamily+" not found");
		DboColumnMeta colMeta = meta.getColumnMeta(column);
		if(colMeta == null)
			throw new IllegalArgumentException("Column="+column+" not found on meta info for column family="+columnFamily);
		else if(!colMeta.isIndexed())
			throw new IllegalArgumentException("Column="+column+" is not an indexed column");
		else if(meta.getPartitionedColumns().size() > 1 && partitionBy == null)
			throw new IllegalArgumentException("Must supply partitionBy parameter BECAUSE this column family="+columnFamily+" is partitioned multiple ways");
		
		ScanInfo info = ScanInfo.createScanInfo(colMeta, partitionBy, partitionId);
		AbstractCursor<IndexColumn> indCol = session.scanIndex(info, null, null, null);
		AbstractCursor<IndexPoint> results = new CursorToIndexPoint(meta.getIdColumnMeta(), colMeta, indCol);
		return results;
	}
	
	@Override
	public QueryResult createQueryCursor(String query, int batchSize) {
		SpiMetaQuery metaQuery = noSqlSessionFactory.parseQueryForAdHoc(query, mgr);
		
		SpiQueryAdapter spiQueryAdapter = metaQuery.createQueryInstanceFromQuery(session); 
		
		spiQueryAdapter.setBatchSize(batchSize);
		Set<ViewInfo> alreadyJoinedViews = new HashSet<ViewInfo>();
		DirectCursor<IndexColumnInfo> iter = spiQueryAdapter.getResultList(alreadyJoinedViews);

		QueryResultImpl impl = new QueryResultImpl(metaQuery, this, iter, batchSize);
		
		return impl;
	}

	@Override
	public TypedRow createTypedRow(String colFamily) {
		DboTableMeta metaClass = cachedMeta.getMeta(colFamily);
		if(metaClass == null)
			throw new IllegalArgumentException("DboTableMeta for colFamily="+colFamily+" was not found");
		
		TypedRow r = new TypedRow(null, metaClass);
		return r;
	}
	@Override
	public int updateQuery(String query) {
		int batchSize = 250;
		QueryResult result = createQueryCursor(query,batchSize);
		Cursor<List<TypedRow>> cursor = result.getAllViewsCursor();
		return updateBatch(cursor, result);
	}

	private int updateBatch(Cursor<List<TypedRow>> cursor, QueryResult result) {
		int rowCount = 0;
		QueryResultImpl impl = (QueryResultImpl) result;
		SpiMetaQuery metaQuery = impl.getMetaQuery();
		List<TypedColumn> updateList = metaQuery.getUpdateList();
		if (updateList.size() == 0)
			throw new IllegalArgumentException("UPDATE should have some values to set");
		while(cursor.next()) {
			List<TypedRow> joinedRow = cursor.getCurrent();
			updateRow(joinedRow, updateList);
			rowCount++;
		}
		return rowCount;
	}

	private void updateRow(List<TypedRow> joinedRow, List<TypedColumn> updateList) {
		for(TypedRow r: joinedRow) {
			ViewInfo view = r.getView();
			DboTableMeta meta = view.getTableMeta();
			for(TypedColumn c : r.getColumnsAsColl()) {
				for (TypedColumn columnforUpdate : updateList ) {
					if (columnforUpdate.getName().equals(c.getName())) {
						Object value = columnforUpdate.getValue();
						c.setValue(value);		
					}
				}
			}
			put(meta.getColumnFamily(), r);	
		}
	}

}