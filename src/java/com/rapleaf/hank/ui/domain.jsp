<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
    pageEncoding="ISO-8859-1"%>

<%@page import="com.rapleaf.hank.coordinator.*"%>
<%@page import="com.rapleaf.hank.ui.*"%>
<%@page import="com.rapleaf.hank.util.*"%>
<%@page import="java.util.*"%>
<%@page import="org.yaml.snakeyaml.*"%>
<%@page import="org.apache.commons.io.*"%>

<%
  Coordinator coord = (Coordinator)getServletContext().getAttribute("coordinator");

  Domain domain = coord.getDomain(URLEnc.decode(request.getParameter("n")));
%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">

<%@page import="org.yaml.snakeyaml.Yaml"%><html>
<head>
  <jsp:include page="_head.jsp" />
  <meta http-equiv="Content-Type" content="text/html; charset=ISO-8859-1">
  <title>Domain: <%= domain.getName() %></title>
</head>
<body>

<jsp:include page="_top_nav.jsp"/>

<h1>Domain <%= domain.getName() %></h1>

<table>
  <tr>
    <td>
      Number of partitions:
    </td>
    <td>
      <%= domain.getNumParts() %>
    </td>
  </tr>
  <tr>
    <td>
      Partitioner:
    </td>
    <td>
      <%= domain.getPartitioner().getClass().getName() %>
    </td>
  </tr>
  <tr>
    <td>
      Storage engine factory:
    </td>
    <td>
      <%= domain.getStorageEngineFactoryClass().getName() %>
    </td>
  </tr>
  <tr valign=top>
    <td>
      Storage engine factory options:
    </td>
    <td>
      <%
      DumperOptions opts = new DumperOptions();
      opts.setExplicitStart(true);
      opts.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
      opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
      %>
      <textarea rows="10" cols="80" disabled=true><%= domain.getStorageEngineOptions() == null
          ? ""
          : new Yaml(opts).dump(domain.getStorageEngineOptions())
          %>
      </textarea>
    </td>
  </tr>
</table>

<h3>Versions</h3>

<div>
  <% if (domain.getOpenedVersion() == null) { %>
  No open version.
  <form method="post" action="/domain/new_version">
    <input type="hidden" name="n" value="<%= domain.getName() %>"/>
    You can force a new version to be created. Note that this will only write the metadata, not any actual data. You should only use this if you know what you're doing!<br/>
    <input type="submit" value="I understand. Open and close a new version."/>
  </form>
  <% } else { %>
  Version #<%= domain.getOpenedVersion().getVersionNumber() %> is currently open.
  <% } %>
</div>

<div>
  Total of <%= FileUtils.byteCountToDisplaySize(domain.getTotalNumBytes()) %> in <%= domain.getVersions().size() %> versions.
</div>

<table class='table-blue'>
  <tr>
    <th>#</th>
    <th>status</th>
    <th>size</th>
    <th># records</th>
    <th></th>
  </tr>
  <%
    SortedSet<DomainVersion> revSorted = new TreeSet<DomainVersion>(new ReverseComparator<DomainVersion>());
    revSorted.addAll(domain.getVersions());
  %>
  <%
    for (DomainVersion version : revSorted) {
  %>
  <tr>
    <td><%= version.getVersionNumber() %></td>
    <td>
      <% if (version.isClosed()) { %>
      Closed
      <%   if (version.isDefunct()) { %>
      DEFUNCT
      <%   } %>
      <%= new Date(version.getClosedAt())%>
      <% } else { %>
      Open - <%= version.getPartitionInfos().size() %>/<%= domain.getNumParts() %> complete
      <% } %>
    </td>
    <td><%= FileUtils.byteCountToDisplaySize(version.getTotalNumBytes()) %></td>
    <td><%= String.format("%,d", version.getTotalNumRecords()) %></td>
    <td>
      <form action="/domain/defunctify" method="post">
        <input type=hidden name="n" value="<%= domain.getName() %>" />
        <input type=hidden name="ver" value="<%= version.getVersionNumber() %>" />
        <input type=submit value="mark defunct" />
      </form>
    </td>
  </tr>
  <% } %>
</table>

</body>
</html>
