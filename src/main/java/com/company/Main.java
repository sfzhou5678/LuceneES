package com.company;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.highlight.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

import java.io.StringReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class Main {
    private Integer ids[] = {1, 2, 3};
    private String citys[] = {"青岛", "南京", "上海"};
    //    private String descs[]={
//            "青岛是一个漂亮的城市。",
//            "南京是一个文化的城市。",
//            "上海是一个繁华的城市。"
//    };
    private String descs[] = {
            "张三是个农民，勤劳致富，奔小康",
            "李四是个企业家，白手起家，致富一方",
            "王五好吃懒做，溜须拍马，跟着李四，也过着小康的日子。"
    };

    private Directory dir;

    /**
     * 实例化indexerWriter
     *
     * @return
     * @throws Exception
     */
    private IndexWriter getWriter() throws Exception {

        //中文分词器
        SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer();

        IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        IndexWriter writer = new IndexWriter(dir, iwc);

        return writer;
    }

    /**
     * 获取indexDir
     *
     * @param indexDir
     * @throws Exception
     */
    private void index(String indexDir) throws Exception {
        dir = FSDirectory.open(Paths.get(indexDir));
        IndexWriter writer = getWriter();

        for (int i = 0; i < ids.length; i++) {
            Document doc = new Document();

            doc.add(new IntField("id", ids[i], Field.Store.YES));
            doc.add(new StringField("city", citys[i], Field.Store.YES));
            doc.add(new TextField("desc", descs[i], Field.Store.YES));  // 注意，这里不能是StringField 否则会搜不出来，原理未知

            writer.addDocument(doc);
        }

        writer.close();
    }

    /**
     * 这是一种不考虑query结构的线性遍历方法，应该没有什么用
     *
     * @param query
     * @param queryList
     */
    public static void traversalBooleanClause(BooleanQuery query, List<TermQuery> queryList) {
        for (BooleanClause subClause : query.getClauses()) {
            if (subClause.getQuery() instanceof BooleanQuery) {
                traversalBooleanClause((BooleanQuery) subClause.getQuery(), queryList);
            } else {
                queryList.add((TermQuery) subClause.getQuery());
                System.out.println(subClause);
            }
        }
    }

    /**
     * 在保留原有结构的前提下，对每个query添加一个权值较低的query_copy语句
     * <p>
     * TODO: 不知道是否所有中间query都是Boolean
     * TODO: 不知道是否所有的叶子结点Query都是TermQuery   A: 应该不是 https://www.cnblogs.com/wangxiangstudy/p/5757408.html,具体还没有研究
     *
     * @param query
     */
    public static void expandBooleanQuery(BooleanQuery query) {
        for (BooleanClause subClause : query.getClauses()) {
            if (subClause.getQuery() instanceof BooleanQuery) {
                expandBooleanQuery((BooleanQuery) subClause.getQuery());
            } else {
                // 对最后的单个term开始做拓展，并将拓展词和原term取并
                // 1. 获取原始term的field和text
                TermQuery oriQuery = (TermQuery) subClause.getQuery();
                Term oriTerm = oriQuery.getTerm();

                // 2. 从相应的field中找到扩充用的信息
                List<TermQuery> newTermQueryList = getRelatedTermQuery(oriTerm.field(), oriTerm.text());


                // 3. 将扩充数据填入newBoolQuery, 并直接更新原始query里对应的值
                BooleanQuery newBoolQuery = new BooleanQuery();
                BooleanClause oriClause = new BooleanClause(oriQuery, BooleanClause.Occur.SHOULD);
                newBoolQuery.add(oriClause);

                for (TermQuery termQuery : newTermQueryList) {
                    BooleanClause newClause = new BooleanClause(termQuery, BooleanClause.Occur.SHOULD);
                    newBoolQuery.add(newClause);
                }
                subClause.setQuery(newBoolQuery);
            }
        }
    }

    private static List<TermQuery> getRelatedTermQuery(String field, String text) {
        List<TermQuery> res = new ArrayList<>();

        // 假设拿到了2个拓展
        for (int i = 0; i < 2; i++) {
            Term newTerm = new Term(field, text + "_copy" + i);
            TermQuery newQuery = new TermQuery(newTerm);
            newQuery.setBoost((float) 0.8); // 设置权重

            res.add(newQuery);
        }

        return res;
    }

    public static void search(String indexDir, String par) throws Exception {
        //得到读取索引文件的路径
        Directory dir = FSDirectory.open(Paths.get(indexDir));
        //通过dir得到的路径下的所有的文件
        IndexReader reader = DirectoryReader.open(dir);
        //建立索引查询器
        IndexSearcher searcher = new IndexSearcher(reader);
        //中文分词器
        SmartChineseAnalyzer analyzer = new SmartChineseAnalyzer();

        //建立查询解析器
        /**
         * 第一个参数是要查询的字段；
         * 第二个参数是分析器Analyzer
         * */
        QueryParser parser = new QueryParser("desc", analyzer);
//        String fields[] = {"desc","city"};
//        MultiFieldQueryParser parser = new MultiFieldQueryParser(fields, analyzer);

        //根据传进来的par查找
        Query query = parser.parse(par);

        // region #...自定义BoolQuery
        //        BooleanQuery query = new BooleanQuery();
//
//        // 对于李四，两种不同的分解情况会得到不同的结果(评分)
//        TermQuery term1 = new TermQuery(new Term("desc", "李四"));
//        TermQuery term1_1 = new TermQuery(new Term("desc", "李"));
//        TermQuery term1_2 = new TermQuery(new Term("desc", "四"));
//
//        TermQuery term2 = new TermQuery(new Term("desc", "小康"));
//        TermQuery term3 = new TermQuery(new Term("desc", "企业家"));
//
//        // 对于复杂的query，需要用这种递归的方式建立
//        BooleanClause clause1_1 = new BooleanClause(term1_1, BooleanClause.Occur.SHOULD);
//        BooleanClause clause1_2 = new BooleanClause(term1_2, BooleanClause.Occur.SHOULD);
//        BooleanQuery tmpQuery = new BooleanQuery();
//        tmpQuery.add(clause1_1);
//        tmpQuery.add(clause1_2);
//
////        BooleanClause clause1 = new BooleanClause(term1, BooleanClause.Occur.SHOULD);     // 按(李四)分词
//        BooleanClause clause1 = new BooleanClause(tmpQuery, BooleanClause.Occur.SHOULD);    // 按(李|四)分词
//        BooleanClause clause2 = new BooleanClause(term2, BooleanClause.Occur.SHOULD);
//        BooleanClause clause3 = new BooleanClause(term3, BooleanClause.Occur.SHOULD);
//
//        query.add(clause1);
//        query.add(clause2);
//        query.add(clause3);
//
//        // 测试并在最顶层的无关信息是否会影响查询结果  -- A: 用SHOULD的话是会的
//        TermQuery term4 = new TermQuery(new Term("desc", "一些无关信息"));
//        BooleanClause clause4 = new BooleanClause(term4, BooleanClause.Occur.SHOULD);
//        query.add(term4,BooleanClause.Occur.SHOULD);
        // endregion

        // 主动扩展query (遍历)
//        List<TermQuery> queryList = new ArrayList<>();
//        traversalBooleanClause(query, queryList);
//        expandBooleanQuery((BooleanQuery) query); // FIXME: 由于增加了很多项导致最后评分偏低，[可能对于拓展词，需要额外做一次查询，然后将正常的和普通的查询结果取交并集??]

        // TODO: 2018/3/26 自定义评分函数？？

        // 开始查询
        TopDocs topDocs = searcher.search(query, 10);
        System.out.println("共查到" + topDocs.totalHits + "条记录。");

        Highlighter highlighter = getHighlighter(query);
        /**
         * ScoreDoc:是代表一个结果的相关度得分与文档编号等信息的对象。
         * scoreDocs:代表文件的数组
         * @throws Exception
         * */
        for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
            //获取文档
            Document document = searcher.doc(scoreDoc.doc);

            //输出全路径
            System.out.println(document.get("city"));
            System.out.println(document.get("desc"));

            System.out.println("score:" + scoreDoc.score);

            // 输出具体的评分原因
            Explanation explanation = searcher.explain(query, scoreDoc.doc);
            System.out.println(explanation.toString());
//            // 下面是用来highlight的，暂时不管
//            String desc = document.get("desc");
//            if (desc != null) {
//                //第一个参数是对哪个参数进行设置；第二个是以流的方式读入
//                TokenStream tokenStream = analyzer.tokenStream("desc", new StringReader(desc));
//                //获取最高的片段
//                System.out.println(highlighter.getBestFragment(tokenStream, desc));
//            }
        }

        reader.close();
    }

    private static Highlighter getHighlighter(Query query) {
        //算分
        QueryScorer scorer = new QueryScorer(query);

        //显示得分高的片段
        Fragmenter fragmenter = new SimpleSpanFragmenter(scorer);

        //设置标签内部关键字的颜色
        //第一个参数：标签的前半部分；第二个参数：标签的后半部分。
        SimpleHTMLFormatter simpleHTMLFormatter = new SimpleHTMLFormatter("<b><font color='red'>", "</font></b>");

        //第一个参数是对查到的结果进行实例化；第二个是片段得分（显示得分高的片段，即摘要）
        Highlighter highlighter = new Highlighter(simpleHTMLFormatter, scorer);

        //设置片段
        highlighter.setTextFragmenter(fragmenter);
        return highlighter;
    }


    public static void main(String[] args) throws Exception {
//        new Main().index("src/main/resources");
//        System.out.println("Success Indexer");

        //索引指定的路径
        String indexDir = "src/main/resources";

        //查询的字段
        String par = "李四 小康 企业家";
        try {
            search(indexDir, par);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
